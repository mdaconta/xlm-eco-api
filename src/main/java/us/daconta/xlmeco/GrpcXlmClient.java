package us.daconta.xlmeco;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.grpc.XlmEcosystemServiceGrpc;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponse;

import java.util.concurrent.CountDownLatch;

public class GrpcXlmClient {

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("Usage: GrpcXlmClient <host> <port> <provider> <modelName> <prompt>");
            System.exit(1);
        }

        // Get the host, port, provider, and prompt from command line arguments
        String host = args[0];       // e.g., "127.0.0.1"
        int port = Integer.parseInt(args[1]);  // e.g., 50051
        String provider = args[2];   // e.g., "openai", "anthropic", or "gemini"
        String modelName = args[3];  // The specific model name, e.g., "gpt-4", "gemini-1.5-flash-001"
        String prompt = args[4];     // The prompt to send

        // Force TCP connection and plaintext communication
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .directExecutor() // Use directExecutor for a simple, local environment
                .build();

        // Create a stub (client) to interact with the service
        XlmEcosystemServiceGrpc.XlmEcosystemServiceBlockingStub stub =
                XlmEcosystemServiceGrpc.newBlockingStub(channel);

        // Prepare a request
        ChatRequest request = ChatRequest.newBuilder()
                .setPrompt(prompt)  // Command line prompt
                .setProvider(provider)    // Command line model
                .build();

        // Call the service and get a response
        ChatResponse response = stub.syncChat(request);

        // Print the response from the server
        System.out.println("Response from the syncChat method: " + response.getCompletion());

        System.out.println("Now, let's test the asyncChat...");

        // Create an asynchronous stub for streaming calls
        XlmEcosystemServiceGrpc.XlmEcosystemServiceStub asyncStub =
                XlmEcosystemServiceGrpc.newStub(channel);

        CountDownLatch latch = new CountDownLatch(1);

        // Prepare a request for the asynchronous call, passing in provider and modelName
        ChatRequest asyncRequest = ChatRequest.newBuilder()
                .setPrompt(prompt)       // Use the prompt from the command line
                .setProvider(provider)   // Use the provider from the command line
                .setModelName(modelName) // Use the model name from the command line
                .build();

        asyncStub.asyncChat(asyncRequest, new StreamObserver<ChatResponsePart>() {
                    @Override
                    public void onNext(ChatResponsePart responsePart) {
                        System.out.print(responsePart.getToken()); // Tokens might be partial words; printing without newline
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.err.println("Error: " + t.getMessage());
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("\nStreaming completed.");
                        latch.countDown();
                    }
                });

        // Wait for asyncChat() to complete
        try {
            System.out.println("\nWaiting for the async method to complete...");
            latch.await();
        } catch (InterruptedException e) {
            System.err.println("Error waiting for streaming to complete: " + e.getMessage());
        }

        // Shutdown the channel after use
        channel.shutdownNow();
    }
}
