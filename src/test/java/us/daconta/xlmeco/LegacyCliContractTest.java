package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LegacyCliContractTest {
    static <T> void reply(StreamObserver<T> out,T value) { out.onNext(value); out.onCompleted(); }
    static class ChatFixture extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {
        boolean embedding=true, failStream; String id; ChatRequest chat; int embeddingCalls; boolean removed;
        public void registerClient(ClientRegistrationRequest r,StreamObserver<ClientRegistrationResponse> o) { id=r.getClientId(); reply(o,ClientRegistrationResponse.newBuilder().setSuccess(true).build()); }
        public void listProviders(EmptyRequest r,StreamObserver<ProvidersListResponse> o) { reply(o,ProvidersListResponse.newBuilder().addProviders(ProviderInfo.newBuilder().setProviderName("openai").putCapabilities("chat",true)).build()); }
        public void getProviderCapabilities(ProviderRequest r,StreamObserver<ProviderCapabilitiesResponse> o) { reply(o,ProviderCapabilitiesResponse.newBuilder().setProviderName("openai").putCapabilities("embedding",embedding).build()); }
        public void setPreferredProviders(ProviderSelectionRequest r,StreamObserver<SelectionResponse> o) { reply(o,SelectionResponse.newBuilder().setSuccess(true).build()); }
        public void syncChat(ChatRequest r,StreamObserver<ChatResponse> o) { chat=r; reply(o,ChatResponse.newBuilder().setCompletion("fixture completion").build()); }
        public void asyncChat(ChatRequest r,StreamObserver<ChatResponsePart> o) {
            o.onNext(ChatResponsePart.newBuilder().setToken("fixture token").build());
            if(failStream) o.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException()); else o.onCompleted();
        }
        public void getEmbedding(EmbeddingRequest r,StreamObserver<EmbeddingResponse> o) { embeddingCalls++; reply(o,EmbeddingResponse.newBuilder().addEmbedding(1).build()); }
        public void unregisterClient(ClientUnregistrationRequest r,StreamObserver<ClientUnregistrationResponse> o) { removed=r.getClientId().equals(id); reply(o,ClientUnregistrationResponse.newBuilder().setSuccess(true).build()); }
    }
    @Test void javaSampleClientTransmitsSelectionAndHandlesStreamFailure() throws Exception {
        for(boolean success:List.of(true,false)) {
            ChatFixture fixture=new ChatFixture(); fixture.embedding=success; fixture.failStream=!success;
            Server server=ServerBuilder.forPort(0).addService(fixture).build().start();
            try {
                GrpcXlmClient.main(new String[]{"127.0.0.1",""+server.getPort(),"openai","fixture-model","hello"});
                assertEquals("fixture-model",fixture.chat.getModelName()); assertEquals("hello",fixture.chat.getPrompt());
                assertEquals("openai",fixture.chat.getProvider()); assertEquals(fixture.id,fixture.chat.getClientId());
                assertEquals(success?1:0,fixture.embeddingCalls); assertTrue(fixture.removed);
            } finally { server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
        }
    }
    static class VectorFixture extends VectorDbServiceGrpc.VectorDbServiceImplBase {
        boolean fail; DefineVectorSchemaRequest schema; List<UpsertVectorRequest> inserts=new ArrayList<>(); String deleted;
        public void defineVectorSchema(DefineVectorSchemaRequest r,StreamObserver<DefineVectorSchemaResponse> o) {
            schema=r; if(fail) o.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException()); else reply(o,DefineVectorSchemaResponse.newBuilder().setSuccess(true).build());
        }
        public void upsertVector(UpsertVectorRequest r,StreamObserver<UpsertVectorResponse> o) { inserts.add(r); reply(o,UpsertVectorResponse.newBuilder().setId(r.getId()).build()); }
        public void getVector(GetVectorRequest r,StreamObserver<GetVectorResponse> o) { reply(o,GetVectorResponse.newBuilder().setId(r.getId()).addEmbedding(1).setContent("content").build()); }
        public void searchVectors(SearchVectorsRequest r,StreamObserver<SearchVectorsResponse> o) { reply(o,SearchVectorsResponse.newBuilder().addResults(SearchResult.newBuilder().setId("vector-1").setScore(.5f)).build()); }
        public void deleteVector(DeleteVectorRequest r,StreamObserver<DeleteVectorResponse> o) { deleted=r.getId(); reply(o,DeleteVectorResponse.newBuilder().setSuccess(true).build()); }
    }
    @Test void vectorSampleExercisesPublicRpcSequenceAndStopsOnFailure() throws Exception {
        for(boolean fail:List.of(false,true)) {
            VectorFixture fixture=new VectorFixture(); fixture.fail=fail;
            Server server=ServerBuilder.forPort(0).addService(fixture).build().start();
            try {
                VectorDbTestClient.main(new String[]{"127.0.0.1",""+server.getPort()});
                assertEquals(8,fixture.schema.getEmbeddingDimension());
                assertEquals(fail?0:2,fixture.inserts.size());
                if(!fail) { assertEquals("test1",fixture.inserts.get(0).getMetadataOrThrow("category").getStringValue()); assertEquals("vector-1",fixture.deleted); }
            } finally { server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
        }
    }
}
