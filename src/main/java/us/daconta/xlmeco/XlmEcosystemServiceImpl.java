package us.daconta.xlmeco;

import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.ChatProvider;
import us.daconta.xlmeco.provider.EmbeddingProvider;
import us.daconta.xlmeco.provider.GenerativeProvider;
import us.daconta.xlmeco.provider.GenerativeProviderFactory;
import us.daconta.xlmeco.provider.impl.GoogleProvider;
import us.daconta.xlmeco.provider.impl.OpenAIProvider;
import us.daconta.xlmeco.provider.impl.GrokProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class XlmEcosystemServiceImpl extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {
    // In-memory map for registered clients (can be replaced with a database)
    private final Map<String, String> registeredClients = new ConcurrentHashMap<>();  // client_id -> client_name
    private final Map<String, Map<String, GenerativeProvider>> clientProviderMap = new ConcurrentHashMap<>();  // client_id -> (capability -> provider)
    private Map<String, GenerativeProvider> providers = new ConcurrentHashMap<String, GenerativeProvider>();
    private static final Logger logger = Logger.getLogger(XlmEcosystemServiceImpl.class.getName());

    public XlmEcosystemServiceImpl(Properties properties) {
        this.providers = GenerativeProviderFactory.loadProviders(properties);
        logger.info(() -> "Loaded providers: " + providers.keySet());
    }

    private Properties filterPropertiesForPrefix(Properties properties, String prefix) {
        Properties subset = new Properties();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                subset.put(name.substring(prefix.length()), properties.getProperty(name));
            }
        }
        return subset;
    }

    @Override
    public void registerClient(ClientRegistrationRequest request, StreamObserver<ClientRegistrationResponse> responseObserver) {
        String clientId = request.getClientId();
        String clientName = request.getClientName();
        logger.info(() -> "Registering client. id=" + clientId + ", name=" + clientName);

        if (registeredClients.containsKey(clientId)) {
            ClientRegistrationResponse response = ClientRegistrationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Client already registered with ID: " + clientId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        // Store the client ID and name
        registeredClients.put(clientId, clientName == null || clientName.isEmpty() ? "Unknown" : clientName);
        logger.info(() -> "Client registered. id=" + clientId + ", name=" + registeredClients.get(clientId));

        ClientRegistrationResponse response = ClientRegistrationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Client registered successfully with ID: " + clientId)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void unregisterClient(ClientUnregistrationRequest request, StreamObserver<ClientUnregistrationResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Unregistering client. id=" + clientId);

        if (!registeredClients.containsKey(clientId)) {
            ClientUnregistrationResponse response = ClientUnregistrationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Client not found with ID: " + clientId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        // Remove the client
        registeredClients.remove(clientId);
        clientProviderMap.remove(clientId); // Remove associated provider choices
        logger.info(() -> "Client unregistered. id=" + clientId);

        ClientUnregistrationResponse response = ClientUnregistrationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Client unregistered successfully with ID: " + clientId)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void syncChat(ChatRequest request, StreamObserver<ChatResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Received sync chat request from client " + clientId + " with prompt size " + request.getPrompt().length());
        if (!registeredClients.containsKey(clientId)) {
            responseObserver.onError(new UnsupportedOperationException("Client Id" + clientId + " is not registered."));
            return;
        }

        // Check if the client has a provider for the "chat" capability
        Map<String, GenerativeProvider> clientProviders = clientProviderMap.get(clientId);
        if (clientProviders == null || !clientProviders.containsKey("chat")) {
            responseObserver.onError(new IllegalArgumentException("No provider selected for 'chat' capability for client: " + clientId));
            return;
        }

        // Retrieve the provider for the "chat" capability
        GenerativeProvider provider = clientProviders.get("chat");

        if (!provider.supportsChat()) {
            responseObserver.onError(new UnsupportedOperationException("Chat is not supported by this provider."));
            return;
        }

        ChatProvider chatProvider = (ChatProvider) provider;
        String completion;
        try {
            completion = chatProvider.generateChatResponse(request);
        } catch (Exception e) {
            completion = "Error: " + e.getMessage();
            logger.log(Level.SEVERE, "Error generating chat response", e);
        }

        ChatResponse response = ChatResponse.newBuilder().setCompletion(completion).build();
        logger.info(() -> "Returning sync chat response for client " + clientId);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void asyncChat(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Received async chat request from client " + clientId + " with prompt size " + request.getPrompt().length());
        if (!registeredClients.containsKey(clientId)) {
            responseObserver.onError(new UnsupportedOperationException("Client Id" + clientId + " is not registered."));
            return;
        }

        // Check if the client has a provider for the "chat" capability
        Map<String, GenerativeProvider> clientProviders = clientProviderMap.get(clientId);
        if (clientProviders == null || !clientProviders.containsKey("chat")) {
            responseObserver.onError(new IllegalArgumentException("No provider selected for 'chat' capability for client: " + clientId));
            return;
        }

        // Retrieve the provider for the "chat" capability
        GenerativeProvider provider = clientProviders.get("chat");

        if (!provider.supportsChat()) {
            responseObserver.onError(new UnsupportedOperationException("Chat is not supported by this provider."));
            return;
        }

        ChatProvider chatProvider = (ChatProvider) provider;
        try {
            chatProvider.streamChatResponse(request, responseObserver);
        } catch (Exception e) {
            responseObserver.onError(new RuntimeException("Error: " + e.getMessage()));
            logger.log(Level.SEVERE, "Error streaming chat response", e);
        }
    }

    @Override
    public void listProviders(EmptyRequest request, StreamObserver<ProvidersListResponse> responseObserver) {
        logger.info("Listing available providers");
        List<ProviderInfo> providerInfos = new ArrayList<>();

        for (GenerativeProvider provider : providers.values()) {
            Map<String, Boolean> capabilities = provider.getSupportedCapabilities();

            ProviderInfo providerInfo = ProviderInfo.newBuilder()
                    .setProviderName(provider.getProviderName())
                    .setServiceLevel(provider.getServiceLevel().name())
                    .putAllCapabilities(capabilities)
                    .build();

            providerInfos.add(providerInfo);
        }

        ProvidersListResponse response = ProvidersListResponse.newBuilder()
                .addAllProviders(providerInfos)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        logger.info(() -> "Returned " + providerInfos.size() + " providers");
    }

    @Override
    public void getProviderCapabilities(ProviderRequest request, StreamObserver<ProviderCapabilitiesResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Fetching capabilities for provider " + request.getProvider() + " for client " + clientId);
        if (!registeredClients.containsKey(clientId)) {
            responseObserver.onError(new UnsupportedOperationException("Client Id" + clientId + " is not registered."));
            return;
        }

        GenerativeProvider provider = GenerativeProviderFactory.getProvider(request.getProvider());

        if (provider == null) {
            responseObserver.onError(new IllegalArgumentException("Provider not found: " + request.getProvider()));
            return;
        }

        // Build response using the provider's capabilities map
        ProviderCapabilitiesResponse response = ProviderCapabilitiesResponse.newBuilder()
                .setProviderName(provider.getProviderName())
                .setServiceLevel(provider.getServiceLevel().name())
                .putAllCapabilities(provider.getSupportedCapabilities())  // Using the map
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        logger.info(() -> "Returned capabilities for provider " + provider.getProviderName());
    }

    @Override
    public void setPreferredProviders(ProviderSelectionRequest request, StreamObserver<SelectionResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Setting preferred providers for client " + clientId);

        // Ensure the client is registered
        if (!registeredClients.containsKey(clientId)) {
            SelectionResponse response = SelectionResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Client not registered with ID: " + clientId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        // Get the existing provider map for the client or create a new one if not present
        Map<String, GenerativeProvider> clientProviders = clientProviderMap.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>());

        // Iterate over the provider-capabilities map in the request
        for (Map.Entry<String, ProviderCapabilitiesRequest> entry : request.getProviderCapabilitiesMap().entrySet()) {
            String providerName = entry.getKey();
            GenerativeProvider provider = GenerativeProviderFactory.getProvider(providerName);
            logger.info(() -> "Assigning provider " + providerName + " to capabilities " + entry.getValue().getCapabilitiesList());

            // Iterate over the list of capabilities the client wants this provider to handle
            for (String capability : entry.getValue().getCapabilitiesList()) {
                // Save the provider for the specific capability in the client's map
                clientProviders.put(capability, provider);
            }
        }

        // Save the updated provider map for the client back into clientProviderMap
        clientProviderMap.put(clientId, clientProviders);

        // Respond to the client
        SelectionResponse response = SelectionResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Preferred providers set successfully for client: " + clientId)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        logger.info(() -> "Preferred providers set for client " + clientId);
    }

    @Override
    public void getEmbedding(us.daconta.xlmeco.grpc.EmbeddingRequest request,
                             io.grpc.stub.StreamObserver<us.daconta.xlmeco.grpc.EmbeddingResponse> responseObserver) {
        String clientId = request.getClientId();
        logger.info(() -> "Received embedding request from client " + clientId + " with text length " + request.getText().length());

        // Check if client is registered
        if (!isClientRegistered(clientId)) {
            throw new IllegalArgumentException("Client not registered: " + clientId);
        }

        // Get the provider for the "embedding" capability
        EmbeddingProvider provider = (EmbeddingProvider) getProviderForCapability(clientId, "embedding");
        if (provider == null) {
            throw new IllegalArgumentException("No provider selected for 'embedding' capability for client: " + clientId);
        }

        // Ensure the provider supports embeddings
        if (!provider.supportsEmbeddings()) {
            throw new UnsupportedOperationException("Selected provider does not support 'embedding' capability.");
        }

        // Process the embedding request
        List<Float> embedding = provider.generateEmbedding(request.getText(), request.getModelParameters());
        EmbeddingResponse response = EmbeddingResponse.newBuilder().addAllEmbedding(embedding).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        logger.info(() -> "Returned embedding of size " + embedding.size() + " for client " + clientId);
        return;
    }

    private boolean isClientRegistered(String clientId) {
        return registeredClients.containsKey(clientId);
    }

    private GenerativeProvider getProviderForCapability(String clientId, String capability) {
        Map<String, GenerativeProvider> clientProviders = clientProviderMap.get(clientId);

        if (clientProviders == null || !clientProviders.containsKey(capability)) {
            return null; // No provider selected for this capability
        }

        return clientProviders.get(capability);
    }
}

