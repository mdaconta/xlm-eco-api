package us.daconta.xlmeco;

import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponse;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.grpc.XlmEcosystemServiceGrpc;
import us.daconta.xlmeco.provider.ChatProvider;
import us.daconta.xlmeco.provider.GenerativeProviderFactory;

public class XlmEcosystemServiceImpl extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {

    @Override
    public void syncChat(ChatRequest request, StreamObserver<ChatResponse> responseObserver) {
        ChatProvider chatProvider = (ChatProvider) GenerativeProviderFactory.getProvider(request.getProvider());
        String completion;
        try {
            completion = chatProvider.generateChatResponse(request);
        } catch (Exception e) {
            completion = "Error: " + e.getMessage();
        }

        ChatResponse response = ChatResponse.newBuilder().setCompletion(completion).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void asyncChat(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) {
        ChatProvider chatProvider = (ChatProvider) GenerativeProviderFactory.getProvider(request.getProvider());
        try {
            chatProvider.streamChatResponse(request, responseObserver);
        } catch (Exception e) {
            responseObserver.onError(new RuntimeException("Error: " + e.getMessage()));
        }
    }
}

