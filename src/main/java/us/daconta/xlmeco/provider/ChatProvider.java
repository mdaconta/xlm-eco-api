package us.daconta.xlmeco.provider;

import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import io.grpc.stub.StreamObserver;

public interface ChatProvider extends GenerativeProvider {

    // Synchronous chat response (aligned with `syncChat` in protobuf)
    String generateChatResponse(ChatRequest request) throws Exception;

    // Asynchronous chat response streaming (aligned with `asyncChat` in protobuf)
    void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws Exception;
}
