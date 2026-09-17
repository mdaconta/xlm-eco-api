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


def generate_chat_title(chat_content):
    """Use syncChat to generate a short title for the conversation."""
    print("Generating title for the chat content...", flush=True)

    title_prompt = f"Generate a concise 3-5 word title for this response:\n{chat_content}\n  Only return that Title in your response."

    chat_request = pb2.ChatRequest(
        client_id=client_id,
        prompt=title_prompt,
        provider=provider,
        model_name=model_name
    )

    try:
        response = stub.syncChat(chat_request)
        return response.completion.strip()
    except grpc.RpcError as e:
        print("Title request failed", flush=True)
        return "Untitled"
    except Exception as e:
        print("Title request failed", flush=True)
        return "Untitled"


def stream_responses(chat_request):
    """Stream responses from gRPC server and emit to the client."""
    chat_content = []
    try:
        response_stream = stub.asyncChat(chat_request)

        # Stream each token and build the full response
        for response_part in response_stream:
            chat_content.append(response_part.token)
            socketio.emit('chat_response', {'message': response_part.token})
            socketio.sleep(0)  # Allow WebSocket to send the message

        # Generate a title after the chat completes
        full_response = "".join(chat_content).strip()
        title = generate_chat_title(full_response)
        socketio.emit('chat_title', {'title': title})

    except grpc.RpcError as e:
        print("Chat request failed", flush=True)
    except Exception as e:
        print("Chat request failed", flush=True)


@app.route('/')
def index():
    return render_template('index.html', ui_token=ui_token, provider=provider, model_name=model_name)


@app.route('/send_message', methods=['POST'])
def send_message():
    if not valid_ui_token():
        return jsonify({'error': 'Invalid UI token'}), 403
    prompt = (request.get_json(silent=True) or {}).get('message')

    if not prompt:
        print("Error: Prompt cannot be empty", flush=True)
        return jsonify({"error": "Prompt cannot be empty"}), 400

    unavailable = inference_unavailable()
    if unavailable is not None:
        return unavailable

    chat_request = pb2.ChatRequest(
        client_id=client_id,
        prompt=prompt,
        provider=provider,
        model_name=model_name
    )

    # Start the streaming in a background thread
    threading.Thread(target=stream_responses, args=(chat_request,)).start()

    return jsonify({"status": "streaming started"}), 200


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
    return trusted_request() and isinstance(auth, dict) and hmac.compare_digest(str(auth.get('token', '')), ui_token)


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

