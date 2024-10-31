package us.daconta.xlmeco;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.GenerativeProvider;

import java.util.Map;
import java.util.UUID;
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

        String clientId = UUID.randomUUID().toString();
        String clientName = "java-client-1";
        System.out.println("Registering a client - name: " + clientName + ", ID: " + clientId);
        ClientRegistrationRequest clientRegistrationRequest = ClientRegistrationRequest.newBuilder().setClientName(clientName).setClientId(clientId).build();
        // Register the client
        ClientRegistrationResponse registrationResponse = stub.registerClient(clientRegistrationRequest);
        if (!registrationResponse.getSuccess()) {
            System.out.println("Failed to register the client! Aborting...");
            System.exit(1);
        } else {
            System.out.println("Successfully registered the client!");
        }

        ProvidersListResponse listResponse = stub.listProviders(EmptyRequest.newBuilder().build());
        for (ProviderInfo providerInfo : listResponse.getProvidersList()) {
            System.out.println("Provider: " + providerInfo.getProviderName());
            System.out.println("Service Level: " + providerInfo.getServiceLevel());
            for (Map.Entry<String, Boolean> capability : providerInfo.getCapabilitiesMap().entrySet()) {
                System.out.println("Capability: " + capability.getKey() + " -> " + capability.getValue());
            }
        }

        System.out.println("Now test the getProvider() method:");
        ProviderRequest providerRequest = ProviderRequest.newBuilder().setClientId(clientId).setProvider("openai").build();
        ProviderCapabilitiesResponse providerCapabilities = stub.getProviderCapabilities(providerRequest);

        System.out.println("Provider: " + providerCapabilities.getProviderName());
        System.out.println("Service Level: " + providerCapabilities.getServiceLevel());
        for (Map.Entry<String, Boolean> capability : providerCapabilities.getCapabilitiesMap().entrySet()) {
            System.out.println("Capability: " + capability.getKey() + " -> " + capability.getValue());
        }

        // Set my preferred provider for the Chat capability
        System.out.println("Now test the setProvider method:");
        ProviderSelectionRequest providerSelectionRequest = ProviderSelectionRequest.newBuilder()
                .setClientId(clientId)
                .putProviderCapabilities(provider, ProviderCapabilitiesRequest.newBuilder()
                        .addCapabilities("chat")
                        .addCapabilities("embedding").build())
                .build();
        SelectionResponse selectionResponse2 = stub.setPreferredProviders(providerSelectionRequest);
        if (!selectionResponse2.getSuccess()) {
            System.out.println("Failed to set the provider!  Aborting...");
            System.exit(1);
        }

        // Prepare a request
        ChatRequest request = ChatRequest.newBuilder()
                .setClientId(clientId)
                .setModelName(modelName)
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
                .setClientId(clientId)
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

        // See if provider supports embeddings
        ProviderRequest providerRequest2 = ProviderRequest.newBuilder()
                .setClientId(clientId)
                .setProvider(provider)
                .build();

        ProviderCapabilitiesResponse providerCapabilitiesResponse = stub.getProviderCapabilities(providerRequest2);
        Map<String, Boolean> capabilitiesMap = providerCapabilitiesResponse.getCapabilitiesMap();
        if (capabilitiesMap.containsKey(GenerativeProvider.CAPABILITY_EMBEDDING)
        && capabilitiesMap.get(GenerativeProvider.CAPABILITY_EMBEDDING).equals(Boolean.TRUE)) {
            // Test the Embedding API
            EmbeddingRequest embeddingRequest = EmbeddingRequest.newBuilder()
                    .setClientId(clientId)
                    .setText("Mickey Mouse is a Disney cartoon character.")
                    .build();

            EmbeddingResponse embeddingResponse = stub.getEmbedding(embeddingRequest);
            System.out.print("Embedding returned:");
            for (float f : embeddingResponse.getEmbeddingList()) {
                System.out.print(f);
            }
            System.out.println();
        } else {
            System.out.println("Provider: " + provider + " does not support the embedding capability.");
        }

        // Now unregister the client
        System.out.println("Unregistering the client...");
        ClientUnregistrationRequest clientUnregistrationRequest = ClientUnregistrationRequest.newBuilder().setClientId(clientId).build();
        ClientUnregistrationResponse clientUnregistrationResponse = stub.unregisterClient(clientUnregistrationRequest);

        // Shutdown the channel after use
        channel.shutdownNow();
    }
}
