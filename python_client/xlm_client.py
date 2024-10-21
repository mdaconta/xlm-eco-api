import grpc
import argparse
import xlm_eco_api_pb2
import xlm_eco_api_pb2_grpc

def run(prompt, provider):
    # Establish the channel to communicate with the gRPC server
    channel = grpc.insecure_channel('localhost:50051')

    # Create a stub (client) to call the XlmEcosystemService
    stub = xlm_eco_api_pb2_grpc.XlmEcosystemServiceStub(channel)

    # Prepare a request message with provider (e.g., openai, gemini)
    chat_request = xlm_eco_api_pb2.ChatRequest(prompt=prompt, model=provider)

    # Call the Chat method from the server and get the response
    response = stub.Chat(chat_request)

    # Output the response from the server
    print("Received completion: ", response.completion)

if __name__ == '__main__':
    # Set up argument parsing for command-line inputs
    parser = argparse.ArgumentParser(description="gRPC client for XlmEcosystemService")
    parser.add_argument('--provider', type=str, required=True, help="Provider name (e.g., openai, gemini)")
    parser.add_argument('--prompt', type=str, required=True, help="The prompt for the chat service")

    # Parse the command-line arguments
    args = parser.parse_args()

    # Run the client with the provided arguments
    run(args.prompt, args.provider)

