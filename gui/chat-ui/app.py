import base64
import sys
import os
import uuid
import grpc
import threading
import hmac
import json
import secrets
import time
import logging
from pathlib import Path
from flask import Flask, render_template, request, jsonify
from flask_socketio import SocketIO
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'python_client'))
from admin_transport import channel as secure_channel, AdminClient
sys.path.insert(0, str(Path(__file__).resolve().parent))
from console_session import ConsoleSession
from google.protobuf.json_format import MessageToDict
import xlm_eco_api_pb2 as pb2
import xlm_eco_api_pb2_grpc as pb2_grpc

app = Flask(__name__, template_folder=str(Path(__file__).resolve().parent / 'templates'),
            static_folder=str(Path(__file__).resolve().parent / 'static'))
app.config['MAX_CONTENT_LENGTH'] = 4 * 1024 * 1024
socketio = SocketIO(app, async_mode='threading')
ui_token = secrets.token_urlsafe(32)
MAX_IMAGE_BYTES = 3 * 1024 * 1024
MAX_TEXT_BYTES = 64 * 1024
SUPPORTED_MIME = {'image/png', 'image/jpeg', 'image/webp'}

stub = None
client_id = None
provider = None
model_name = None
admin = None
session = None
stream_sessions = {}
stream_lock = threading.Lock()
app.config['UI_HOSTS'] = {'localhost', '127.0.0.1'}


def configure_client(host, port, selected_provider, selected_model):
    """Start bounded recovery; an unavailable XLM does not prevent opening the Console."""
    global stub, client_id, provider, model_name, session
    channel = secure_channel(f"{host}:{port}", os.environ.get("XLM_INFERENCE_CA_FILE"))
    stub = pb2_grpc.XlmEcosystemServiceStub(channel)
    client_id = str(uuid.uuid4())
    provider, model_name = selected_provider, selected_model
    session = ConsoleSession(stub, client_id, provider, admin)
    session.start()
    return channel


@app.route('/console/status')
def console_status():
    if session is None:
        return jsonify({'inference': {'state': 'unconfigured', 'code': ''},
                        'administration': {'state': 'unconfigured', 'code': ''},
                        'chat': {'state': 'unconfigured', 'code': ''}, 'generation': 0})
    return jsonify(session.status())


def inference_unavailable():
    if session is not None and not session.check_inference():
        return jsonify({'error': 'XLM connection is recovering; request was not submitted',
                        'connection': session.status()['inference']}), 503
    return None


def valid_ui_token():
    supplied = request.headers.get('X-XLM-UI-Token', '')
    return hmac.compare_digest(supplied, ui_token)


def invalid_request(message):
    return jsonify({'error': message}), 400


@app.errorhandler(413)
def request_too_large(_error):
    return jsonify({'error': 'Request exceeds 4 MiB limit'}), 413


def generate_chat_title(chat_request, content):
    """Preserve the existing optional title using the request's explicit model."""
    try:
        result = stub.syncChat(pb2.ChatRequest(client_id=chat_request.client_id,
            prompt='Generate a concise 3-5 word title for this response:\n' + content + '\nOnly return that title.',
            provider=chat_request.provider, model_name=chat_request.model_name), timeout=30)
        return result.completion.strip() or 'Untitled'
    except Exception:
        return 'Untitled'


def stream_responses(chat_request, sid, request_id):
    """Keep every event scoped to the originating connection and transcript entry."""
    content = []
    try:
        for part in stub.asyncChat(chat_request, timeout=160):
            content.append(part.token)
            socketio.emit('chat_response', {'request_id': request_id, 'message': part.token}, to=sid)
            socketio.sleep(0)
        socketio.emit('chat_done', {'request_id': request_id}, to=sid)
        socketio.emit('chat_title', {'request_id': request_id,
                      'title': generate_chat_title(chat_request, ''.join(content))}, to=sid)
    except Exception:
        socketio.emit('chat_error', {'request_id': request_id,
                      'message': 'XLM text request failed'}, to=sid)


@app.route('/')
def index():
    return render_template('index.html', ui_token=ui_token, provider=provider, model_name=model_name)


def selection(data):
    values = (data.get('provider', provider or ''), data.get('model', model_name or ''))
    if any(not isinstance(v, str) or len(v) > 128 for v in values):
        raise ValueError('Provider and model must be strings of at most 128 characters')
    return values


def transport_failure(error):
    code = error.code()
    name = code.name if code in (grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED) else 'OTHER'
    return jsonify({'error': 'XLM transport request failed', 'transport_code': name}), (504 if code == grpc.StatusCode.DEADLINE_EXCEEDED else 502)


@app.route('/models')
def models():
    unavailable = inference_unavailable()
    if unavailable is not None:
        return unavailable
    try:
        result = stub.listModels(pb2.ModelCatalogRequest(client_id=client_id), timeout=10)
        return jsonify(MessageToDict(result, preserving_proto_field_name=True,
                                     always_print_fields_with_no_presence=True))
    except grpc.RpcError as error:
        return transport_failure(error)


@app.route('/send_message', methods=['POST'])
@app.route('/generate_image', methods=['POST'])
def send_message():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return invalid_request('Expected a JSON object')
    prompt = data.get('message')
    if not isinstance(prompt, str) or not prompt.strip() or len(prompt.encode('utf-8')) > MAX_TEXT_BYTES:
        return invalid_request('Prompt is required and limited to 64 KiB')
    if data.get('image'):
        return invalid_request('Use image analysis for attachments; image editing is not supported')
    try:
        selected_provider, selected_model = selection(data)
    except ValueError as error:
        return invalid_request(str(error))
    unavailable = inference_unavailable()
    if unavailable is not None:
        return unavailable
    if request.path == '/generate_image':
        started = time.monotonic()
        try:
            result = stub.generateImage(pb2.ImageGenerationRequest(client_id=client_id,
                prompt=prompt, provider=selected_provider, model=selected_model), timeout=160)
        except grpc.RpcError as error:
            return transport_failure(error)
        body = {'success': result.success, 'provider': result.provider, 'model': result.model,
                'request_id': result.request_id, 'latency_ms': round((time.monotonic() - started) * 1000),
                'error_code': pb2.StructuredImageErrorCode.Name(result.error.code),
                'retryable': result.error.retryable}
        if result.success:
            if (result.output_type != pb2.GENERATED_IMAGE or result.image.mime_type not in ('image/png', 'image/jpeg')
                    or not result.image.data or len(result.image.data) > MAX_IMAGE_BYTES):
                return jsonify({'error': 'XLM returned an invalid generated image'}), 502
            body.update(output_type='image', mime_type=result.image.mime_type,
                        image_base64=base64.b64encode(result.image.data).decode('ascii'))
        return jsonify(body)
    stream_token = data.get('stream_session')
    with stream_lock:
        sid = stream_sessions.get(stream_token) if isinstance(stream_token, str) else None
    request_id = data.get('request_id')
    if not sid or not isinstance(request_id, str) or not 1 <= len(request_id) <= 128:
        return invalid_request('Connect to chat before sending a text request')
    chat_request = pb2.ChatRequest(client_id=client_id, prompt=prompt,
                                  provider=selected_provider, model_name=selected_model)
    threading.Thread(target=stream_responses, args=(chat_request, sid, request_id), daemon=True).start()
    return jsonify({'status': 'streaming started', 'request_id': request_id})


@app.route('/analyze_image', methods=['POST'])
def analyze_image():
    if not valid_ui_token():
        return jsonify({'error': 'Invalid UI token'}), 403
    images = request.files.getlist('image')
    if len(images) != 1 or len(request.files) != 1:
        return invalid_request('Select exactly one image')
    image = images[0]
    if image.mimetype not in SUPPORTED_MIME:
        return invalid_request('Use PNG, JPEG, or WebP')
    instructions = request.form.get('instructions', '').strip()
    schema_text = request.form.get('json_schema', '').strip()
    if (not instructions or not schema_text
            or len(instructions.encode('utf-8')) > MAX_TEXT_BYTES
            or len(schema_text.encode('utf-8')) > MAX_TEXT_BYTES):
        return invalid_request('Instructions and JSON Schema are required and limited to 64 KiB each')
    try:
        if not isinstance(json.loads(schema_text), dict):
            return invalid_request('JSON Schema must be an object')
    except ValueError:
        return invalid_request('JSON Schema must be a valid JSON object')
    image_bytes = image.stream.read(MAX_IMAGE_BYTES + 1)
    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        return invalid_request('Image must be nonempty and no larger than 3 MiB')
    unavailable = inference_unavailable()
    if unavailable is not None:
        return unavailable
    grpc_request = pb2.StructuredImageRequest(
        client_id=client_id, instructions=instructions,
        image=pb2.ImageInput(mime_type=image.mimetype, data=image_bytes),
        provider=request.form.get('provider', provider or ''),
        model=request.form.get('model', model_name or ''), json_schema=schema_text)
    started = time.monotonic()
    try:
        result = stub.generateStructuredImage(grpc_request, timeout=90)
    except grpc.RpcError as error:
        code = error.code()
        name = code.name if code in (grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED) else 'OTHER'
        status = 504 if code == grpc.StatusCode.DEADLINE_EXCEEDED else 502
        return jsonify({'error': 'XLM transport request failed', 'transport_code': name}), status
    elapsed_ms = round((time.monotonic() - started) * 1000)
    try:
        structured_result = json.loads(result.json_payload) if result.success else None
        if result.success and not isinstance(structured_result, dict):
            raise ValueError('Expected object')
    except ValueError:
        return jsonify({'error': 'XLM returned invalid structured JSON'}), 502
    return jsonify({
        'success': result.success,
        'status': pb2.StructuredImageStatus.Name(result.status),
        'structured_result': structured_result,
        'provider': result.provider,
        'model': result.model,
        'latency_ms': elapsed_ms,
        'error_code': pb2.StructuredImageErrorCode.Name(result.error.code),
        'error_message': result.error.message,
        'retryable': result.error.retryable,
        'provider_http_status': result.error.provider_http_status,
    })




def trusted_request():
    # Exact authority matching, including port; never trust forwarded headers.
    return (request.host in app.config['UI_HOSTS'] and
            (not request.headers.get('Origin') or
             request.headers['Origin'] == 'http://' + request.host))


@app.before_request
def protect_console():
    if not trusted_request():
        return jsonify({'error': 'Untrusted UI origin or host'}), 403
    if request.method not in ('GET', 'HEAD', 'OPTIONS') and not valid_ui_token():
        return jsonify({'error': 'Invalid UI token'}), 403


@socketio.on('connect')
def socket_connect(auth=None):
    if not (trusted_request() and isinstance(auth, dict) and hmac.compare_digest(str(auth.get('token', '')), ui_token)):
        return False
    token = secrets.token_urlsafe(32)
    with stream_lock:
        stream_sessions[token] = request.sid
    socketio.emit('stream_session', {'token': token}, to=request.sid)
    return True


@socketio.on('disconnect')
def socket_disconnect(reason=None):
    with stream_lock:
        expired = [token for token, sid in stream_sessions.items() if sid == request.sid]
        for token in expired:
            del stream_sessions[token]


@app.route('/admin')
def admin_page():
    return render_template('admin.html', ui_token=ui_token)


@app.route('/admin/api/<operation>', methods=['GET', 'POST'])
def admin_api(operation):
    if admin is None:
        return jsonify({'error': 'Administration is not configured'}), 503
    reads = {
        'providers': ('listProviders', lambda d: pb2.AdminListProvidersRequest(capability=d.get('capability', ''))),
        'provider': ('getProvider', lambda d: pb2.AdminProviderRequest(provider=d.get('provider', ''))),
        'models': ('listModels', lambda d: pb2.AdminListModelsRequest(provider=d.get('provider', ''), capability=d.get('capability', ''))),
        'defaults': ('getDefaults', lambda d: pb2.EmptyRequest()),
        'status': ('getStatus', lambda d: pb2.EmptyRequest()),
    }
    writes = {
        'provider': ('updateProvider', lambda d: pb2.AdminUpdateProviderRequest(**d)),
        'model': ('upsertModel', lambda d: pb2.AdminUpsertModelRequest(**d)),
        'default': ('setDefault', lambda d: pb2.AdminSetDefaultRequest(**d)),
        'reload': ('reloadCredentials', lambda d: pb2.AdminRevision(**d)),
    }
    entry = (reads if request.method == 'GET' else writes).get(operation)
    if entry is None:
        return jsonify({'error': 'Unknown operation'}), 404
    data = request.args.to_dict() if request.method == 'GET' else request.get_json(silent=True)
    if not isinstance(data, dict):
        return invalid_request('Expected a JSON object')
    try:
        result = admin.call(entry[0], entry[1](data))
        return jsonify(MessageToDict(result, preserving_proto_field_name=True,
                                      always_print_fields_with_no_presence=True))
    except (TypeError, ValueError):
        return invalid_request('Malformed administrative update')
    except grpc.RpcError as error:
        code = error.code().name
        return jsonify({'error': 'Administrative request failed', 'code': code}), (401 if code == 'UNAUTHENTICATED' else 409 if code == 'ABORTED' else 400)


if __name__ == '__main__':
    if len(sys.argv) != 6:
        print("Usage: python app.py <grpc_host> <grpc_port> <flask_port> <provider> <model_name>", flush=True)
        sys.exit(1)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(name)s %(message)s')
    grpc_host, grpc_port, flask_port, selected_provider, selected_model = sys.argv[1:]
    app.config['UI_HOSTS'] = {f'127.0.0.1:{flask_port}', f'localhost:{flask_port}'}
    admin_channel = None
    if os.environ.get('XLM_ADMIN_ENDPOINT'):
        admin_channel = secure_channel(os.environ['XLM_ADMIN_ENDPOINT'], os.environ.get('XLM_ADMIN_CA_FILE'))
        admin = AdminClient(pb2_grpc.XlmAdminServiceStub(admin_channel), os.environ.get('XLM_ADMIN_TOKEN_FILE'))
    grpc_channel = configure_client(grpc_host, grpc_port, selected_provider, selected_model)
    print(f"Starting Flask server on port {flask_port}", flush=True)
    try:
        socketio.run(app, host="127.0.0.1", port=int(flask_port), debug=False, allow_unsafe_werkzeug=True)
    finally:
        if session:
            session.close()
        grpc_channel.close()
        if admin_channel:
            admin_channel.close()

