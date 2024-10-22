import grpc
import argparse
import xlm_eco_api_pb2
import xlm_eco_api_pb2_grpc

def run(host, port, provider, model_name, prompt):
    # Establish the channel to communicate with the gRPC server
    channel = grpc.insecure_channel(f'{host}:{port}')

    # Create a stub (client) to call the XlmEcosystemService
    stub = xlm_eco_api_pb2_grpc.XlmEcosystemServiceStub(channel)

    # Prepare a request message with provider (e.g., openai, gemini)
    # Prepare a request message with provider, model name, and prompt
    chat_request = xlm_eco_api_pb2.ChatRequest(
        prompt=prompt,
        provider=provider,
        model_name=model_name
    )

    # Call the syncChat method from the server and get the response
    response = stub.syncChat(chat_request)

    # Output the response from the syncChat method
    print("Response from syncChat:")
    print(response.completion)

    print("\nNow, let's test the asyncChat...")
    # Call the asyncChat method and get the response iterator
    response_iterator = stub.asyncChat(chat_request)

    # Output the streaming responses from the server
    print("Received streaming completion:")
    try:
        for response_part in response_iterator:
            print(response_part.token, end='', flush=True)
    except grpc.RpcError as e:
        print(f"\nError occurred: {e.details()}")
    finally:
        print("\nStreaming completed.")

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

