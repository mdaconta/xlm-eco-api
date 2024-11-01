package us.daconta.xlmeco.provider.impl;

import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.grpc.ModelParameters;
import us.daconta.xlmeco.provider.ChatProvider;
import us.daconta.xlmeco.provider.EmbeddingProvider;

import java.util.List;
import java.util.Properties;

public class AnthropicProvider extends AbstractGenerativeProvider implements ChatProvider, EmbeddingProvider {
    public static final String PROVIDER_NAME = "anthropic";
    Properties configProperties;

    public AnthropicProvider() {
    }

    public void initialize(Properties configProperties) {
        this.configProperties = configProperties;
    }

    @Override
    public String generateChatResponse(ChatRequest request) throws Exception {
        return "";
    }

    @Override
    public void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws Exception {

    }

    @Override
    public List<Float> generateEmbedding(String text, ModelParameters parameters) {
        return List.of();
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public ServiceLevel getServiceLevel() {
        return ServiceLevel.LEVEL_1;
    }

    @Override
    public boolean supportsChat() {
        return false;
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public boolean supportsRAG() {
        return false;
    }

    @Override
    public boolean supportsAgents() {
        return false;
    }
}
