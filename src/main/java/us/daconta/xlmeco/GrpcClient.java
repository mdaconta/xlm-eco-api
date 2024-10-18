package us.daconta.xlmeco;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import us.daconta.xlmeco.grpc.XlmEcosystemServiceGrpc;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponse;

public class GrpcClient {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: GrpcClient <model> <prompt>");
            System.exit(1);
        }

        // Get the model and prompt from command line arguments
        String model = args[0]; // e.g., "openai", "anthropic", or "gemini"
        String prompt = args[1]; // The prompt to send

        // Force TCP connection and plaintext communication
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", 50051)
                .usePlaintext()
                .directExecutor() // Use directExecutor for a simple, local environment
                .build();

        // Create a stub (client) to interact with the service
        XlmEcosystemServiceGrpc.XlmEcosystemServiceBlockingStub stub =
                XlmEcosystemServiceGrpc.newBlockingStub(channel);

        // Prepare a request
        ChatRequest request = ChatRequest.newBuilder()
                .setPrompt(prompt)  // Command line prompt
                .setModel(model)    // Command line model
                .build();

        // Call the service and get a response
        ChatResponse response = stub.chat(request);

        // Print the response from the server
        System.out.println("Response from server: " + response.getCompletion());

        // Shutdown the channel after use
        channel.shutdown();
    }
}
