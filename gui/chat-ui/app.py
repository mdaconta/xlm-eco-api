import sys
import uuid
import grpc
import threading
from flask import Flask, render_template, request, jsonify
from flask_socketio import SocketIO
import xlm_eco_api_pb2 as pb2
import xlm_eco_api_pb2_grpc as pb2_grpc

app = Flask(__name__)
socketio = SocketIO(app, async_mode='threading')

if len(sys.argv) != 6:
    print("Usage: python app.py <grpc_host> <grpc_port> <flask_port> <provider> <model_name>", flush=True)
    sys.exit(1)

grpc_host = sys.argv[1]
grpc_port = sys.argv[2]
flask_port = int(sys.argv[3])
provider = sys.argv[4]
model_name = sys.argv[5]

server_address = f"{grpc_host}:{grpc_port}"
print(f"Connecting to gRPC server at {server_address}", flush=True)

channel = grpc.insecure_channel(server_address)
stub = pb2_grpc.XlmEcosystemServiceStub(channel)

# Register the client
client_id = str(uuid.uuid4())
client_name = "flask-client-1"
print(f"Registering client with name: {client_name}, ID: {client_id}", flush=True)
client_registration_request = pb2.ClientRegistrationRequest(
    client_name=client_name,
    client_id=client_id
)
registration_response = stub.registerClient(client_registration_request)
if not registration_response.success:
    raise RuntimeError("Failed to register the client!")
print("Client registered successfully.", flush=True)

# Set preferred providers
print("Now testing setProvider...", flush=True)
provider_selection_request = pb2.ProviderSelectionRequest(
    client_id=client_id,
    provider_capabilities={provider: pb2.ProviderCapabilitiesRequest(
        capabilities=["chat", "embedding"]
    )}
)
selection_response = stub.setPreferredProviders(provider_selection_request)
if not selection_response.success:
    print(f"Failed to set the provider to {provider}! Aborting...", flush=True)
else:
    print(f"Successfully set the provider to: {provider}", flush=True)


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
        print(f"Error generating title: {e.code()} - {e.details()}", flush=True)
        return "Untitled"
    except Exception as e:
        print(f"Unexpected error while generating title: {e}", flush=True)
        return "Untitled"


def stream_responses(chat_request):
    """Stream responses from gRPC server and emit to the client."""
    chat_content = []
    try:
        response_stream = stub.asyncChat(chat_request)

        # Stream each token and build the full response
        for response_part in response_stream:
            print(f"Received token from gRPC: {response_part.token}", flush=True)
            chat_content.append(response_part.token)
            socketio.emit('chat_response', {'message': response_part.token})
            socketio.sleep(0)  # Allow WebSocket to send the message

        # Generate a title after the chat completes
        full_response = "".join(chat_content).strip()
        title = generate_chat_title(full_response)
        socketio.emit('chat_title', {'title': title})

    except grpc.RpcError as e:
        print(f"gRPC error: {e.code()} - {e.details()}", flush=True)
    except Exception as e:
        print(f"Unexpected error: {e}", flush=True)


@app.route('/')
def index():
    return render_template('index.html')


@app.route('/send_message', methods=['POST'])
def send_message():
    prompt = request.json.get('message')

    if not prompt:
        print("Error: Prompt cannot be empty", flush=True)
        return jsonify({"error": "Prompt cannot be empty"}), 400

    print(f"Using client_id: {client_id}", flush=True)
    print(f"Sending this prompt to gRPC server: {prompt}", flush=True)
    print(f"Provider: {provider}, Model Name: {model_name}", flush=True)

    chat_request = pb2.ChatRequest(
        client_id=client_id,
        prompt=prompt,
        provider=provider,
        model_name=model_name
    )

    # Start the streaming in a background thread
    threading.Thread(target=stream_responses, args=(chat_request,)).start()

    return jsonify({"status": "streaming started"}), 200


if __name__ == '__main__':
    app.config['PROPAGATE_EXCEPTIONS'] = True
    print(f"Starting Flask server on port {flask_port}", flush=True)
    socketio.run(app, host="0.0.0.0", port=flask_port, debug=True, allow_unsafe_werkzeug=True)

