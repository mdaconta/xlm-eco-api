package us.daconta.xlmeco.admin;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.XlmEcosystemServiceImpl;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.security.AdminAuthInterceptor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminIntegrationTest {
    static final String TOKEN="synthetic-admin-test-token-000000000000";
    @Test void adminApiFiltersUpdatesValidationAndAllMethodsRequireAuth() throws Exception {
        try(var registry=ProviderRegistry.inMemory(new Properties())) {
            var service=new AdminService(registry);
            Server server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service,new AdminAuthInterceptor(TOKEN))).build().start();
            ManagedChannel channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
            try {
                // Exercise every current Admin method without metadata at the wire boundary.
                for(var definition:service.bindService().getMethods()) {
                    @SuppressWarnings("unchecked") var descriptor=(MethodDescriptor<Object,Object>)definition.getMethodDescriptor();
                    Object request=descriptor.getRequestMarshaller().parse(new java.io.ByteArrayInputStream(new byte[0]));
                    var error=assertThrows(StatusRuntimeException.class,()->io.grpc.stub.ClientCalls.blockingUnaryCall(channel,descriptor,CallOptions.DEFAULT,request));
                    assertEquals(Status.Code.UNAUTHENTICATED,error.getStatus().getCode());
                }
                Metadata metadata=new Metadata();metadata.put(Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER),"Bearer "+TOKEN);
                var stub=XlmAdminServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
                var providers=stub.listProviders(AdminListProvidersRequest.newBuilder().setCapability("structured_image").build());
                assertEquals(3,providers.getProvidersCount());assertFalse(providers.toString().contains(TOKEN));
                var models=stub.listModels(AdminListModelsRequest.newBuilder().setProvider("google").setCapability("structured_image").build());
                assertEquals(3,models.getModelsCount());assertTrue(models.getModelsList().stream().allMatch(m->m.getProvider().equals("google")));
                assertEquals(3,stub.getDefaults(EmptyRequest.getDefaultInstance()).getDefaultsCount());
                var updated=stub.updateProvider(AdminUpdateProviderRequest.newBuilder().setProvider("google").setEnabled(false).setExpectedRevision(1).putConfiguration("timeout_seconds","120").build());
                assertEquals(2,updated.getRevision());assertFalse(stub.getProvider(AdminProviderRequest.newBuilder().setProvider("google").build()).getEnabled());
                assertEquals(Status.Code.ABORTED,assertThrows(StatusRuntimeException.class,()->stub.reloadCredentials(AdminRevision.newBuilder().setRevision(1).build())).getStatus().getCode());
                assertEquals(Status.Code.INVALID_ARGUMENT,assertThrows(StatusRuntimeException.class,()->stub.updateProvider(AdminUpdateProviderRequest.newBuilder().setProvider("google").setExpectedRevision(2).putConfiguration("api_key",TOKEN).build())).getStatus().getCode());
                assertEquals(2,stub.getStatus(EmptyRequest.getDefaultInstance()).getRevision());
                var model=AdminModel.newBuilder().setProvider("google").setModel("new-image").setEnabled(true).addCapabilities("structured_image").build();
                stub.upsertModel(AdminUpsertModelRequest.newBuilder().setModel(model).setExpectedRevision(2).build());
                assertEquals(Status.Code.FAILED_PRECONDITION,assertThrows(StatusRuntimeException.class,()->stub.setDefault(AdminSetDefaultRequest.newBuilder().setSelection(AdminDefault.newBuilder().setCapability("structured_image").setProvider("google").setModel("new-image")).setExpectedRevision(3).build())).getStatus().getCode());
                stub.updateProvider(AdminUpdateProviderRequest.newBuilder().setProvider("google").setEnabled(true).setExpectedRevision(3).build());
                stub.setDefault(AdminSetDefaultRequest.newBuilder().setSelection(AdminDefault.newBuilder().setCapability("structured_image").setProvider("google").setModel("new-image")).setExpectedRevision(4).build());
                stub.setDefault(AdminSetDefaultRequest.newBuilder().setSelection(AdminDefault.newBuilder().setCapability("structured_image")).setClear(true).setExpectedRevision(5).build());
                assertEquals(2,stub.getDefaults(EmptyRequest.getDefaultInstance()).getDefaultsCount());
                assertEquals(7,stub.reloadCredentials(AdminRevision.newBuilder().setRevision(6).build()).getRevision());
            } finally {channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
        }
    }
    @Test void googleFailureCannotExposeCredentialThroughServiceOrLogs() throws Exception {
        String marker="synthetic_google_key_marker_123"; AtomicReference<String> uri=new AtomicReference<>(),key=new AtomicReference<>();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/models/",exchange->{uri.set(exchange.getRequestURI().toString());key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] bytes=("{\"error\":\""+marker+"\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(403,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});http.start();
        Properties props=new Properties();props.setProperty("google.api_key",marker);props.setProperty("google.chat_url","http://127.0.0.1:"+http.getAddress().getPort()+"/models/");
        StringBuilder logs=new StringBuilder();Handler handler=new Handler(){public void publish(LogRecord r){logs.append(r.getMessage());if(r.getThrown()!=null)logs.append(r.getThrown().toString());}public void flush(){}public void close(){}};
        Logger root=Logger.getLogger("");root.addHandler(handler);
        try(var registry=ProviderRegistry.inMemory(props)) {
            Server server=ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(registry)).build().start();
            ManagedChannel channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
            try {
                var stub=XlmEcosystemServiceGrpc.newBlockingStub(channel);stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId("test").build());
                var error=assertThrows(StatusRuntimeException.class,()->stub.syncChat(ChatRequest.newBuilder().setClientId("test").setProvider("google").setModelName("gemini-2.5-flash").setPrompt("test").build()));
                assertEquals(Status.Code.UNAVAILABLE,error.getStatus().getCode());assertFalse(error.toString().contains(marker));
                assertTrue(error.getStatus().getDescription().contains("google"));
                assertTrue(error.getStatus().getDescription().contains("403"));
                assertTrue(error.getStatus().getDescription().contains("PROVIDER_AUTHENTICATION"));
                assertEquals(marker,key.get());assertFalse(uri.get().contains(marker));assertFalse(uri.get().contains("key="));assertFalse(logs.toString().contains(marker));
            } finally {channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
        } finally {root.removeHandler(handler);http.stop(0);}
    }
    @Test void legacyProviderSyncAndStreamErrorsAreSafeAndNeverSuccess() throws Exception {
        String marker="synthetic_provider_secret_marker";
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/failure",exchange->{byte[] bytes=("{\"error\":\""+marker+"\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(429,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});http.start();
        Properties props=new Properties();
        for(String provider:List.of("openai","grok","ollama")) {
            props.setProperty(provider+".api_key",marker);
            props.setProperty(provider+".chat_url","http://127.0.0.1:"+http.getAddress().getPort()+"/failure");
        }
        try(var registry=ProviderRegistry.inMemory(props)) {
            Server server=ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(registry)).build().start();
            ManagedChannel channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
            try {
                var stub=XlmEcosystemServiceGrpc.newBlockingStub(channel);
                stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId("test").build());
                for(var pair:Map.of("openai","gpt-4.1","grok","grok-beta","ollama","llama3").entrySet()) {
                    var request=ChatRequest.newBuilder().setClientId("test").setProvider(pair.getKey()).setModelName(pair.getValue()).setPrompt("test").build();
                    for(boolean streaming:List.of(false,true)) {
                        var failure=assertThrows(StatusRuntimeException.class,()-> {
                            if(streaming)stub.asyncChat(request).hasNext();else stub.syncChat(request);
                        });
                        assertEquals(Status.Code.UNAVAILABLE,failure.getStatus().getCode());
                        String message=failure.getStatus().getDescription();
                        assertFalse(message.contains(marker));assertTrue(message.contains(pair.getKey()));
                        assertTrue(message.contains("429"));assertTrue(message.contains("PROVIDER_RATE_LIMIT"));
                    }
                }
            } finally {channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
        } finally {http.stop(0);}
    }
    @Test void requestsSwitchImmediatelyAndOverrideDefaultsWithoutPreferences() throws Exception {
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<String> body=new AtomicReference<>();
        http.createContext("/chat",exchange->{body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"color\\\":\\\"red\\\"}\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});http.start();
        Properties props=new Properties();props.setProperty("openai.api_key","synthetic");props.setProperty("openai.chat_url","http://127.0.0.1:"+http.getAddress().getPort()+"/chat");
        try(var registry=ProviderRegistry.inMemory(props)) {
            Server server=ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(registry)).build().start();ManagedChannel ch=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
            try {
                var stub=XlmEcosystemServiceGrpc.newBlockingStub(ch);stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId("test").build());
                var request=StructuredImageRequest.newBuilder().setClientId("test").setInstructions("color?").setJsonSchema("{\"type\":\"object\"}").setImage(ImageInput.newBuilder().setMimeType("image/png").setData(ByteString.copyFrom(new byte[]{(byte)137,80,78,71,13,10,26,10}))).build();
                assertEquals("gpt-4o-mini",stub.generateStructuredImage(request).getModel());
                registry.setDefault(1,"structured_image",new ProviderRegistry.Selection("openai","gpt-4.1-mini"));
                assertEquals("gpt-4.1-mini",stub.generateStructuredImage(request).getModel());
                var explicit=request.toBuilder().setProvider("openai").setModel("gpt-4.1-nano").build();assertTrue(stub.generateStructuredImage(explicit).getSuccess());assertTrue(body.get().contains("gpt-4.1-nano"));
                registry.upsertModel(2,new ProviderRegistry.Model("openai","gpt-4.1-nano",false,Set.of("chat","structured_image"),""));
                assertEquals(StructuredImageErrorCode.MODEL_DISABLED,stub.generateStructuredImage(explicit).getError().getCode());
                registry.updateProvider(3,"google",false,Map.of());assertEquals(StructuredImageErrorCode.PROVIDER_DISABLED,stub.generateStructuredImage(explicit.toBuilder().setProvider("google").setModel("gemini-2.5-flash").build()).getError().getCode());
                stub.setPreferredProviders(ProviderSelectionRequest.newBuilder().setClientId("test").putProviderCapabilities("openai",ProviderCapabilitiesRequest.newBuilder().addCapabilities("chat").build()).build());
                assertFalse(stub.syncChat(ChatRequest.newBuilder().setClientId("test").setModelName("gpt-4.1").setPrompt("legacy").build()).getCompletion().isEmpty());
            } finally {ch.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
        } finally {http.stop(0);}
    }
}
