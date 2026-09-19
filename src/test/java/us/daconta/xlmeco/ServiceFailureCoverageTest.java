package us.daconta.xlmeco;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import org.junit.jupiter.api.*;
import us.daconta.xlmeco.admin.ProviderRegistry;
import us.daconta.xlmeco.grpc.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises public RPC validation and legacy routing against real loopback HTTP/gRPC. */
class ServiceFailureCoverageTest {
    HttpServer http; Server server; ManagedChannel channel; ProviderRegistry registry;
    XlmEcosystemServiceGrpc.XlmEcosystemServiceBlockingStub stub;
    volatile String reply = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
    volatile int status = 200;
    static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    @BeforeEach void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); http.start();
        Properties p=new Properties(); p.setProperty("openai.api_key","synthetic");
        for(String url:List.of("chat_url","image_url","embedding_url")) p.setProperty("openai."+url,"http://127.0.0.1:"+http.getAddress().getPort()+"/");
        registry=ProviderRegistry.inMemory(p);
        server=ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(registry)).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        stub=XlmEcosystemServiceGrpc.newBlockingStub(channel);
        assertTrue(stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId("c").build()).getSuccess());
    }
    @AfterEach void close() throws Exception {
        channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        registry.close();http.stop(0);
    }
    StructuredImageRequest.Builder structured() {
        return StructuredImageRequest.newBuilder().setClientId("c").setProvider("openai").setModel("gpt-4o-mini")
                .setInstructions("Describe").setJsonSchema("{}").setImage(ImageInput.newBuilder().setMimeType("image/png").setData(ByteString.copyFrom(PNG)));
    }
    ImageGenerationRequest.Builder generation() {
        return ImageGenerationRequest.newBuilder().setClientId("c").setProvider("openai").setModel("gpt-image-1").setPrompt("Draw");
    }
    void code(StructuredImageRequest.Builder r,StructuredImageErrorCode code) {
        var response=stub.generateStructuredImage(r.build());assertFalse(response.getSuccess());assertEquals(code,response.getError().getCode());
    }
    @Test void registrationPreferencesAndUnregistrationEnforceState() {
        for(String id:List.of("", " ","c","x".repeat(129)))
            assertFalse(stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId(id).build()).getSuccess());
        for(String provider:List.of("missing","anthropic")) {
            if(provider.equals("anthropic"))registry.updateProvider(registry.snapshot().state().revision(),provider,false,Map.of());
            assertFalse(stub.setPreferredProviders(ProviderSelectionRequest.newBuilder().setClientId("c")
                    .putProviderCapabilities(provider,ProviderCapabilitiesRequest.newBuilder().addCapabilities("chat").build()).build()).getSuccess());
        }
        var invalid=ProviderSelectionRequest.newBuilder().setClientId("c")
                .putProviderCapabilities("openai",ProviderCapabilitiesRequest.newBuilder().addCapabilities("bogus").build());
        assertFalse(stub.setPreferredProviders(invalid.build()).getSuccess());
        var duplicate=ProviderSelectionRequest.newBuilder().setClientId("c")
                .putProviderCapabilities("openai",ProviderCapabilitiesRequest.newBuilder().addCapabilities("chat").build())
                .putProviderCapabilities("google",ProviderCapabilitiesRequest.newBuilder().addCapabilities("chat").build());
        assertFalse(stub.setPreferredProviders(duplicate.build()).getSuccess());
        assertFalse(stub.setPreferredProviders(ProviderSelectionRequest.newBuilder().setClientId("unknown").build()).getSuccess());
        assertTrue(stub.setPreferredProviders(ProviderSelectionRequest.newBuilder().setClientId("c")
                .putProviderCapabilities("openai",ProviderCapabilitiesRequest.newBuilder().addCapabilities("chat").addCapabilities("embedding").build()).build()).getSuccess());
        assertEquals("{}",stub.syncChat(ChatRequest.newBuilder().setClientId("c").setPrompt("Use preferred provider").build()).getCompletion());
        assertEquals("{}",stub.syncChat(ChatRequest.newBuilder().setClientId("c").setModelName("gpt-4o-mini").setPrompt("Use preferred provider with explicit model").build()).getCompletion());
        assertTrue(stub.unregisterClient(ClientUnregistrationRequest.newBuilder().setClientId("c").build()).getSuccess());
        assertFalse(stub.unregisterClient(ClientUnregistrationRequest.newBuilder().setClientId("c").build()).getSuccess());
        assertEquals(Status.Code.FAILED_PRECONDITION,assertThrows(StatusRuntimeException.class,()->stub.syncChat(ChatRequest.newBuilder().setClientId("c").build())).getStatus().getCode());
    }
    @Test void discoveryExposesDisabledModelsAndProvidersWithoutMutation() {
        registry.upsertModel(registry.snapshot().state().revision(),new ProviderRegistry.Model("openai","disabled-image",false,Set.of("image_generation"),"Disabled"));
        registry.updateProvider(registry.snapshot().state().revision(),"anthropic",false,Map.of());
        var all=stub.listModels(ModelCatalogRequest.newBuilder().setClientId("c").build());
        assertTrue(all.getModelsList().stream().anyMatch(m->m.getModel().equals("disabled-image")&&!m.getEnabled()));
        assertTrue(all.getModelsList().stream().filter(m->m.getProvider().equals("anthropic")).noneMatch(ModelCatalogEntry::getEnabled));
        var caps=stub.getProviderCapabilities(ProviderRequest.newBuilder().setClientId("c").setProvider("anthropic").build());
        assertTrue(caps.getCapabilitiesMap().values().stream().noneMatch(Boolean::booleanValue));
        assertTrue(stub.listProviders(EmptyRequest.getDefaultInstance()).getProvidersList().stream().filter(p->p.getProviderName().equals("anthropic"))
                .allMatch(p->p.getCapabilitiesMap().values().stream().noneMatch(Boolean::booleanValue)));
        assertEquals(Status.Code.NOT_FOUND,assertThrows(StatusRuntimeException.class,()->stub.getProviderCapabilities(ProviderRequest.newBuilder().setClientId("c").setProvider("missing").build())).getStatus().getCode());
    }
    @Test void structuredValidationRejectsEachFieldBoundaryAndInvalidSignatures() {
        List<StructuredImageRequest.Builder> bad=List.of(structured().clearClientId(),structured().setClientId("x".repeat(129)),
                structured().setProvider("x".repeat(129)),structured().setModel("x".repeat(129)),structured().clearInstructions(),
                structured().setInstructions("é".repeat(32769)),structured().clearJsonSchema(),structured().setJsonSchema("{}"+" ".repeat(65537)),
                structured().clearImage(),structured().setJsonSchema("[]"),structured().setJsonSchema("{"));
        for(var r:bad)code(r,StructuredImageErrorCode.INVALID_REQUEST);
        for(String mime:List.of("image/png","image/jpeg","image/webp","image/gif")) {
            code(structured().setImage(ImageInput.newBuilder().setMimeType(mime)),StructuredImageErrorCode.INVALID_REQUEST);
            code(structured().setImage(ImageInput.newBuilder().setMimeType(mime).setData(ByteString.copyFrom(new byte[]{1}))),StructuredImageErrorCode.INVALID_REQUEST);
        }
        byte[][] signatures={PNG,new byte[]{(byte)255,(byte)216,(byte)255,0},new byte[]{82,73,70,70,0,0,0,0,87,69,66,80}};
        String[] mimes={"image/png","image/jpeg","image/webp"};
        int[][] offsets={{0,1,2,3,4,5,6,7},{0,1,2},{0,1,2,3,8,9,10,11}};
        for(int i=0;i<signatures.length;i++) {
            assertTrue(stub.generateStructuredImage(structured().setImage(ImageInput.newBuilder().setMimeType(mimes[i]).setData(ByteString.copyFrom(signatures[i]))).build()).getSuccess());
            for(int offset:offsets[i]) {
                byte[] corrupt=signatures[i].clone();corrupt[offset]=0;
                code(structured().setImage(ImageInput.newBuilder().setMimeType(mimes[i]).setData(ByteString.copyFrom(corrupt))),StructuredImageErrorCode.INVALID_REQUEST);
            }
        }
        code(structured().setImage(ImageInput.newBuilder().setMimeType("image/gif").setData(ByteString.copyFrom(PNG))),StructuredImageErrorCode.INVALID_REQUEST);
        code(structured().setImage(ImageInput.newBuilder().setMimeType("image/png").setData(ByteString.copyFrom(new byte[3*1024*1024+1]))),StructuredImageErrorCode.INVALID_REQUEST);
    }
    @Test void structuredSelectionFailuresAreNormalized() {
        code(structured().setProvider("missing"),StructuredImageErrorCode.UNSUPPORTED_PROVIDER);
        code(structured().setModel("missing"),StructuredImageErrorCode.UNSUPPORTED_MODEL);
        code(structured().setModel("gpt-image-1"),StructuredImageErrorCode.INCOMPATIBLE_CAPABILITY);
        code(structured().setClientId("unknown"),StructuredImageErrorCode.INVALID_REQUEST);
        code(structured().setProvider("google").setModel("gemini-2.5-flash"),StructuredImageErrorCode.PROVIDER_AUTHENTICATION);
        registry.setDefault(registry.snapshot().state().revision(),"chat",null);
        registry.setDefault(registry.snapshot().state().revision(),"structured_image",null);
        registry.upsertModel(registry.snapshot().state().revision(),new ProviderRegistry.Model("openai","gpt-4o-mini",false,Set.of("chat","structured_image"),"Disabled"));
        code(structured(),StructuredImageErrorCode.MODEL_DISABLED);
        registry.updateProvider(registry.snapshot().state().revision(),"anthropic",false,Map.of());
        code(structured().setProvider("anthropic").setModel("claude-sonnet-4-6"),StructuredImageErrorCode.PROVIDER_DISABLED);
    }
    @Test void generationBoundariesAndUpstreamFailuresNeverPublishImages() {
        for(var r:List.of(generation().clearClientId(),generation().setClientId("x".repeat(129)),generation().setProvider("x".repeat(129)),generation().setModel("x".repeat(129))))
            assertEquals(StructuredImageErrorCode.INVALID_REQUEST,stub.generateImage(r.build()).getError().getCode());
        status=429;reply="{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private detail\"}}";
        var quota=stub.generateImage(generation().build());assertEquals(StructuredImageErrorCode.PROVIDER_QUOTA,quota.getError().getCode());
        assertFalse(quota.hasImage());assertFalse(quota.toString().contains("private detail"));assertFalse(quota.getRequestId().isBlank());
        status=200;reply="{\"model\":\"unsafe model\\n\",\"data\":[{\"b64_json\":\""+validPng()+"\"}]}";
        assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,stub.generateImage(generation().build()).getError().getCode());
    }
    String validPng() {
        try {var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return Base64.getEncoder().encodeToString(out.toByteArray());}
        catch(java.io.IOException e){throw new AssertionError(e);}
    }
    @Test void legacyChatStreamingAndEmbeddingRouteThroughRegisteredSelections() {
        var chat=ChatRequest.newBuilder().setClientId("c").setProvider("openai").setModelName("gpt-4o-mini").setPrompt("hello").build();
        assertEquals("{}",stub.syncChat(chat).getCompletion());
        reply="data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n";
        var stream=stub.asyncChat(chat);assertTrue(stream.hasNext());assertEquals("hello",stream.next().getToken());assertFalse(stream.hasNext());
        reply="{\"data\":[{\"embedding\":[0.25,0.5]}]}";
        var embedding=EmbeddingRequest.newBuilder().setClientId("c").setProvider("openai").setText("text");
        assertEquals(List.of(0.25f,0.5f),stub.getEmbedding(embedding.setModel("text-embedding-ada-002").build()).getEmbeddingList());
        assertEquals(2,stub.getEmbedding(embedding.clearModel().setModelParameters(ModelParameters.newBuilder().putParameters("model","text-embedding-ada-002")).build()).getEmbeddingCount());
        status=401;reply="SYNTHETIC_SECRET";
        assertEquals(Status.Code.UNAVAILABLE,assertThrows(StatusRuntimeException.class,()->stub.syncChat(chat)).getStatus().getCode());
        assertEquals(Status.Code.UNAVAILABLE,assertThrows(StatusRuntimeException.class,()->stub.asyncChat(chat).hasNext()).getStatus().getCode());
        assertEquals(Status.Code.UNAVAILABLE,assertThrows(StatusRuntimeException.class,()->stub.getEmbedding(embedding.build())).getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT,assertThrows(StatusRuntimeException.class,()->stub.syncChat(chat.toBuilder().clearProvider().build())).getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND,assertThrows(StatusRuntimeException.class,()->stub.syncChat(chat.toBuilder().setModelName("missing").build())).getStatus().getCode());
    }
}
