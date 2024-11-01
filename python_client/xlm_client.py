import grpc
import argparse
import xlm_eco_api_pb2
import xlm_eco_api_pb2_grpc
import uuid
from concurrent.futures import ThreadPoolExecutor

def run(host, port, provider, model_name, prompt):
    # Establish the channel to communicate with the gRPC server
    channel = grpc.insecure_channel(f'{host}:{port}')

    # Create a stub (client) to call the XlmEcosystemService
    stub = xlm_eco_api_pb2_grpc.XlmEcosystemServiceStub(channel)

    # Register the client
    client_id = str(uuid.uuid4())
    client_name = "python-client-1"
    print(f"Registering client with name: {client_name}, ID: {client_id}", flush=True)

    client_registration_request = xlm_eco_api_pb2.ClientRegistrationRequest(
        client_name=client_name,
        client_id=client_id
    )
    registration_response = stub.registerClient(client_registration_request)
    if not registration_response.success:
        print("Failed to register the client! Aborting...", flush=True)
        return
    print("Successfully registered the client!", flush=True)

    # List providers and their capabilities
    list_response = stub.listProviders(xlm_eco_api_pb2.EmptyRequest())
    for provider_info in list_response.providers:
        print(f"Provider: {provider_info.provider_name}, Service Level: {provider_info.service_level}", flush=True)
        for capability, supported in provider_info.capabilities.items():
            print(f"  Capability: {capability} -> {supported}", flush=True)

    # Get provider capabilities
    print("Now testing getProviderCapabilities...", flush=True)
    provider_request = xlm_eco_api_pb2.ProviderRequest(client_id=client_id, provider=provider)
    provider_capabilities = stub.getProviderCapabilities(provider_request)

    print(f"Provider: {provider_capabilities.provider_name}, Service Level: {provider_capabilities.service_level}", flush=True)
    for capability, supported in provider_capabilities.capabilities.items():
        print(f"  Capability: {capability} -> {supported}", flush=True)

    # Set preferred provider capabilities
    print("Now testing setProvider...", flush=True)
    provider_selection_request = xlm_eco_api_pb2.ProviderSelectionRequest(
        client_id=client_id,
        provider_capabilities={provider: xlm_eco_api_pb2.ProviderCapabilitiesRequest(
            capabilities=["chat", "embedding"]
        )}
    )
    selection_response = stub.setPreferredProviders(provider_selection_request)
    if not selection_response.success:
        print("Failed to set the provider! Aborting...", flush=True)
        return
    else:
        print("Successfully set the provider to: " + provider, flush=True)

    # Prepare a request message with provider (e.g., openai, gemini)
    # Prepare a request message with provider, model name, and prompt
    chat_request = xlm_eco_api_pb2.ChatRequest(
        client_id = client_id,
        prompt=prompt,
        provider=provider,
        model_name=model_name
    )

    # Call the syncChat method from the server and get the response
    response = stub.syncChat(chat_request)

    # Output the response from the syncChat method
    print("Response from syncChat:")
    print(response.completion, flush=True)

    print("\nNow, let's test the asyncChat...", flush=True)
    # Call the asyncChat method and get the response iterator
    response_iterator = stub.asyncChat(chat_request)

    # Output the streaming responses from the server
    print("Received streaming completion:", flush=True)
    try:
        for response_part in response_iterator:
            print(response_part.token, end='', flush=True)
    except grpc.RpcError as e:
        print(f"\nError occurred: {e.details()}")
    finally:
        print("\nStreaming completed.", flush=True)

    # Test embedding capability
    provider_capabilities = stub.getProviderCapabilities(provider_request)
    if provider_capabilities.capabilities.get("embedding"):
        embedding_request = xlm_eco_api_pb2.EmbeddingRequest(
            client_id=client_id,
            text="Mickey Mouse is a Disney cartoon character."
        )
        embedding_response = stub.getEmbedding(embedding_request)
        print("Embedding returned:", flush=True)
        print(" ".join(map(str, embedding_response.embedding)))
    else:
        print(f"Provider {provider} does not support the embedding capability.", flush=True)

    # Unregister the client
    print("Unregistering the client...")
    client_unregistration_request = xlm_eco_api_pb2.ClientUnregistrationRequest(client_id=client_id)
    client_unregistration_response = stub.unregisterClient(client_unregistration_request)
    if client_unregistration_response.success:
        print("Successfully unregistered the client.", flush=True)
    else:
        print("Failed to unregister the client.")

    # Close the channel after the test
    channel.close()

if __name__ == '__main__':
    # Set up argument parsing for command-line inputs
    parser = argparse.ArgumentParser(description="gRPC client for XlmEcosystemService")
    parser.add_argument('--host', type=str, default='localhost', help="Server host (default: localhost)")
    parser.add_argument('--port', type=int, default=50051, help="Server port (default: 50051)")
    parser.add_argument('--provider', type=str, required=True, help="Provider name (e.g., openai, gemini)")
    parser.add_argument('--model_name', type=str, required=True, help="Model name (e.g., gpt-4, gemini-1.5-flash-001)")
    parser.add_argument('--prompt', type=str, required=True, help="The prompt for the chat service")

    # Parse the command-line arguments
    args = parser.parse_args()

    # Run the client with the provided arguments
    run(args.host, args.port, args.provider, args.model_name, args.prompt)

