package us.daconta.xlmeco;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StructuredImageServiceTest {
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"color\":{\"type\":\"string\"}},"
            + "\"required\":[\"color\"],\"additionalProperties\":false}";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> reply = new AtomicReference<>(
            "{\"model\":\"test-vision-revision\",\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"content\":\"{\\\"color\\\":\\\"blue\\\"}\"}}]}");
    private volatile int httpStatus = 200;
    private HttpServer http;
    private Server grpc;
    private ManagedChannel channel;
    private XlmEcosystemServiceGrpc.XlmEcosystemServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        http.start();
        Properties config = new Properties();
        config.setProperty("openai.api_key", "test-key");
        config.setProperty("openai.chat_url", "http://127.0.0.1:" + http.getAddress().getPort() + "/chat");
        config.setProperty("openai.vision_models", "test-vision");
        grpc = ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(config)).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.getPort()).usePlaintext().build();
        stub = XlmEcosystemServiceGrpc.newBlockingStub(channel);
        assertTrue(stub.registerClient(ClientRegistrationRequest.newBuilder()
                .setClientId("test-client").setClientName("test").build()).getSuccess());
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        grpc.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        http.stop(0);
    }

    private StructuredImageRequest request() {
        return StructuredImageRequest.newBuilder().setClientId("test-client")
                .setInstructions("Identify the main color in this image")
                .setImage(ImageInput.newBuilder().setMimeType("image/png").setData(ByteString.copyFrom(PNG)))
                .setProvider("openai").setModel("test-vision").setJsonSchema(SCHEMA).build();
    }

    @Test
    void forwardsOneImageMimeModelAndSchemaAndNormalizesResult() {
        new JSONObject(reply.get());
        StructuredImageResponse result = stub.generateStructuredImage(request());
        assertTrue(result.getSuccess(), result.toString());
        assertEquals(StructuredImageStatus.STRUCTURED_IMAGE_COMPLETED, result.getStatus());
        assertEquals("openai", result.getProvider());
        assertEquals("test-vision-revision", result.getModel());
        assertEquals("blue", new JSONObject(result.getJsonPayload()).getString("color"));
        JSONObject sent = new JSONObject(body.get());
        assertEquals("test-vision", sent.getString("model"));
        var parts = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        assertEquals("Identify the main color in this image", parts.getJSONObject(0).getString("text"));
        assertEquals("image_url", parts.getJSONObject(1).getString("type"));
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"));
        JSONObject format = sent.getJSONObject("response_format");
        assertEquals("json_schema", format.getString("type"));
        assertEquals("xlm_result", format.getJSONObject("json_schema").getString("name"));
        assertTrue(format.getJSONObject("json_schema").getBoolean("strict"));
        assertEquals(new JSONObject(SCHEMA).toString(), format.getJSONObject("json_schema").getJSONObject("schema").toString());
    }

    @Test
    void validatesMediaSchemaAndExplicitSelectionBeforeHttp() {
        assertEquals(StructuredImageErrorCode.INVALID_REQUEST, stub.generateStructuredImage(request().toBuilder()
                .setImage(ImageInput.newBuilder().setMimeType("image/jpeg").setData(ByteString.copyFrom(PNG))).build())
                .getError().getCode());
        assertEquals(StructuredImageErrorCode.INVALID_REQUEST, stub.generateStructuredImage(request().toBuilder()
                .setJsonSchema("[]").build()).getError().getCode());
        assertEquals(StructuredImageErrorCode.INVALID_REQUEST, stub.generateStructuredImage(request().toBuilder()
                .setJsonSchema("{type:'object'}").build()).getError().getCode());
        assertEquals(StructuredImageErrorCode.INVALID_REQUEST, stub.generateStructuredImage(request().toBuilder()
                .setJsonSchema(SCHEMA + " trailing").build()).getError().getCode());
        assertEquals(StructuredImageErrorCode.UNSUPPORTED_MODEL, stub.generateStructuredImage(request().toBuilder()
                .setModel("text-only").build()).getError().getCode());
        assertEquals(StructuredImageErrorCode.UNSUPPORTED_PROVIDER, stub.generateStructuredImage(request().toBuilder()
                .setProvider("missing").build()).getError().getCode());
        assertNull(body.get());
    }

    @Test
    void mapsProviderFailuresWithoutLeakingBody() {
        httpStatus = 429;
        reply.set("secret provider diagnostic");
        StructuredImageResponse limited = stub.generateStructuredImage(request());
        assertFalse(limited.getSuccess());
        assertEquals(StructuredImageStatus.STRUCTURED_IMAGE_FAILED, limited.getStatus());
        assertEquals(StructuredImageErrorCode.PROVIDER_RATE_LIMIT, limited.getError().getCode());
        assertTrue(limited.getError().getRetryable());
        assertEquals(429, limited.getError().getProviderHttpStatus());
        assertFalse(limited.toString().contains("secret provider diagnostic"));
        reply.set("{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"private billing details\"}}");
        StructuredImageResponse quota = stub.generateStructuredImage(request());
        assertEquals(StructuredImageErrorCode.PROVIDER_QUOTA, quota.getError().getCode());
        assertFalse(quota.getError().getRetryable());
        assertFalse(quota.toString().contains("private billing details"));
        httpStatus = 401;
        assertEquals(StructuredImageErrorCode.PROVIDER_AUTHENTICATION,
                stub.generateStructuredImage(request()).getError().getCode());
        httpStatus = 504;
        StructuredImageResponse timedOut = stub.generateStructuredImage(request());
        assertEquals(StructuredImageErrorCode.PROVIDER_TIMEOUT, timedOut.getError().getCode());
        assertTrue(timedOut.getError().getRetryable());
        httpStatus = 503;
        assertEquals(StructuredImageErrorCode.PROVIDER_UNAVAILABLE,
                stub.generateStructuredImage(request()).getError().getCode());
        httpStatus = 200;
        reply.set("not json");
        assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                stub.generateStructuredImage(request()).getError().getCode());
        reply.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not json\"}}]}");
        assertEquals(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                stub.generateStructuredImage(request()).getError().getCode());
        reply.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{color:'blue'}\"}}]}");
        assertEquals(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                stub.generateStructuredImage(request()).getError().getCode());
        reply.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"color\\\":\\\"blue\\\"} trailing\"}}]}");
        assertEquals(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                stub.generateStructuredImage(request()).getError().getCode());
    }

    @Test
    void preservesTextChatAndAcceptsNearLimitImageOverNetwork() throws Exception {
        ChatRequest legacy = ChatRequest.newBuilder().setClientId("test-client")
                .setPrompt("Hello").setProvider("openai").setModelName("text-model").build();
        assertEquals(legacy, ChatRequest.parseFrom(legacy.toByteArray()));
        stub.setPreferredProviders(ProviderSelectionRequest.newBuilder().setClientId("test-client")
                .putProviderCapabilities("openai", ProviderCapabilitiesRequest.newBuilder()
                        .addCapabilities("chat").build()).build());
        reply.set("{\"choices\":[{\"message\":{\"content\":\"legacy works\"}}]}");
        assertEquals("legacy works", stub.syncChat(legacy).getCompletion());
        byte[] nearLimit = Arrays.copyOf(PNG, 3 * 1024 * 1024);
        StructuredImageResponse delivered = stub.generateStructuredImage(request().toBuilder()
                .setClientId("unregistered").setImage(ImageInput.newBuilder()
                        .setMimeType("image/png").setData(ByteString.copyFrom(nearLimit))).build());
        assertEquals(StructuredImageErrorCode.INVALID_REQUEST, delivered.getError().getCode());
        assertEquals("Client is not registered", delivered.getError().getMessage());
    }
}
