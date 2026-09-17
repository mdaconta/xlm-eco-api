package us.daconta.xlmeco;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import us.daconta.xlmeco.provider.impl.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicProviderAdapterTest {
    private static final String SECRET = "synthetic_secret_marker";
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), uri = new AtomicReference<>(), auth = new AtomicReference<>();
    private String response;
    private int status = 200;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location", "/redirected");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private Properties config() {
        Properties p = new Properties();
        p.setProperty("api_key", SECRET);
        p.setProperty("chat_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
        p.setProperty("embedding_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/embedding");
        p.setProperty("default_embedding_model", "embedding-default");
        return p;
    }
    private StructuredImageProvider.Input input(String model) {
        return new StructuredImageProvider.Input("Read image", "image/png", new byte[]{1, 2, 3}, model,
                "{\"type\":\"object\",\"properties\":{\"color\":{\"type\":\"string\"}},\"required\":[\"color\"],\"additionalProperties\":false}");
    }
    @Test void googleForwardsModelImageSchemaAndNormalizes() throws Exception {
        response = "{\"modelVersion\":\"gemini-actual\",\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"color\\\":\\\"blue\\\"}\"}]}}]}";
        Properties properties = config();
        properties.setProperty("chat_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/models/");
        GoogleProvider p = new GoogleProvider(); p.initialize(properties);
        var result = p.generateStructuredImage(input("gemini-requested"));
        assertEquals("/models/gemini-requested:generateContent", uri.get());
        assertEquals("gemini-actual", result.model());
        assertEquals("blue", new JSONObject(result.jsonPayload()).getString("color"));
        JSONObject sent = new JSONObject(body.get());
        assertEquals("application/json", sent.getJSONObject("generationConfig").getString("responseMimeType"));
        assertEquals("object", sent.getJSONObject("generationConfig").getJSONObject("responseJsonSchema").getString("type"));
        assertEquals("AQID", sent.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inline_data").getString("data"));
        assertEquals(SECRET, auth.get()); assertFalse(uri.get().contains(SECRET));
        assertTrue(p.supportsStructuredImageModel("newly-configured-model"));
    }
    @Test void anthropicForwardsModelImageSchemaAndNormalizes() throws Exception {
        response = "{\"model\":\"claude-actual\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"color\\\":\\\"blue\\\"}\"}]}";
        AnthropicProvider p = new AnthropicProvider(); p.initialize(config());
        assertEquals("claude-actual", p.generateStructuredImage(input("claude-requested")).model());
        JSONObject sent = new JSONObject(body.get());
        assertEquals("claude-requested", sent.getString("model"));
        assertEquals("json_schema", sent.getJSONObject("output_config").getJSONObject("format").getString("type"));
        assertEquals("AQID", sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("source").getString("data"));
    }
    @Test void googleSecretsAbsentFromUrlsErrorsCausesAndConsoleLogs() throws Exception {
        GoogleProvider p = new GoogleProvider(); p.initialize(config());
        response = "{\"error\":{\"message\":\"" + SECRET + "\",\"code\":\"" + SECRET + "\"}}"; status = 401;
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream oldOut = System.out, oldErr = System.err;
        try (PrintStream capture = new PrintStream(logs)) {
            System.setOut(capture); System.setErr(capture);
            var chat = assertThrows(Exception.class, () -> p.generateChatResponse(ChatRequest.newBuilder().setModelName("model").setPrompt("hello").build()));
            var embedding = assertThrows(RuntimeException.class, () -> p.generateEmbedding("text", ModelParameters.getDefaultInstance()));
            var image = assertThrows(StructuredImageProvider.Failure.class, () -> p.generateStructuredImage(input("model")));
            for (Throwable error : new Throwable[]{chat, embedding, image}) {
                assertFalse(error.toString().contains(SECRET)); assertNull(error.getCause());
            }
            assertEquals(StructuredImageErrorCode.PROVIDER_AUTHENTICATION, image.code());
            assertFalse(uri.get().contains(SECRET)); assertEquals(SECRET, auth.get());
        } finally { System.setOut(oldOut); System.setErr(oldErr); }
        assertFalse(logs.toString(StandardCharsets.UTF_8).contains(SECRET));
    }
    @Test void malformedTruncatedOversizedAndRedirectResponsesFailSafely() {
        GoogleProvider google = new GoogleProvider(); google.initialize(config());
        AnthropicProvider anthropic = new AnthropicProvider(); anthropic.initialize(config());
        for (StructuredImageProvider p : new StructuredImageProvider[]{google, anthropic}) {
            for (String bad : new String[]{SECRET, "{}", "{} {}", "x".repeat(1_048_577)}) {
                response = bad;
                var error = assertThrows(StructuredImageProvider.Failure.class, () -> p.generateStructuredImage(input("model")));
                assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE, error.code());
                assertFalse(error.toString().contains(SECRET));
            }
            status = 302; response = "redirect";
            assertEquals(StructuredImageErrorCode.PROVIDER_FAILURE, assertThrows(StructuredImageProvider.Failure.class,
                    () -> p.generateStructuredImage(input("model"))).code());
            status = 200;
        }
    }
    @Test void providerErrorsUseOnlyStableClassification() {
        GoogleProvider google = new GoogleProvider(); google.initialize(config());
        AnthropicProvider anthropic = new AnthropicProvider(); anthropic.initialize(config());
        int[] statuses = {401, 403, 429, 408, 504, 500, 400};
        StructuredImageErrorCode[] codes = {StructuredImageErrorCode.PROVIDER_AUTHENTICATION,
                StructuredImageErrorCode.PROVIDER_AUTHENTICATION, StructuredImageErrorCode.PROVIDER_RATE_LIMIT,
                StructuredImageErrorCode.PROVIDER_TIMEOUT, StructuredImageErrorCode.PROVIDER_TIMEOUT,
                StructuredImageErrorCode.PROVIDER_UNAVAILABLE, StructuredImageErrorCode.PROVIDER_FAILURE};
        response = SECRET;
        for (StructuredImageProvider p : new StructuredImageProvider[]{google, anthropic}) {
            for (int i = 0; i < statuses.length; i++) {
                status = statuses[i];
                var error = assertThrows(StructuredImageProvider.Failure.class, () -> p.generateStructuredImage(input("model")));
                assertEquals(codes[i], error.code()); assertEquals(status, error.httpStatus());
                assertFalse(error.toString().contains(SECRET)); assertNull(error.getCause());
            }
        }
    }
    @Test void invalidStructuredOutputAndIncompleteGenerationFail() {
        AnthropicProvider p = new AnthropicProvider(); p.initialize(config());
        response = new JSONObject().put("stop_reason", "end_turn").put("content", new org.json.JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "not-json"))).toString();
        assertEquals(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                assertThrows(StructuredImageProvider.Failure.class, () -> p.generateStructuredImage(input("model"))).code());
        response = new JSONObject(response).put("stop_reason", "max_tokens").toString();
        assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                assertThrows(StructuredImageProvider.Failure.class, () -> p.generateStructuredImage(input("model"))).code());
    }
    @Test void legacyTextFailuresRetainSafeStatusForSyncAndStream() throws Exception {
        response = SECRET; status = 429;
        for (us.daconta.xlmeco.provider.GenerativeProvider provider : new us.daconta.xlmeco.provider.GenerativeProvider[]{
                new OpenAIProvider(), new GrokProvider(), new OllamaProvider(), new GoogleProvider()}) {
            provider.initialize(config());
            var chat = (us.daconta.xlmeco.provider.ChatProvider) provider;
            var request = ChatRequest.newBuilder().setModelName("model").setPrompt("hello").build();
            var sync = assertThrows(us.daconta.xlmeco.provider.SafeProviderFailure.class, () -> chat.generateChatResponse(request));
            var stream = assertThrows(us.daconta.xlmeco.provider.SafeProviderFailure.class, () -> chat.streamChatResponse(request,
                    new io.grpc.stub.StreamObserver<ChatResponsePart>() {
                        public void onNext(ChatResponsePart part) { fail("Error must not be returned as successful content"); }
                        public void onError(Throwable error) { fail("Throw typed failure for service normalization"); }
                        public void onCompleted() { fail("Failed request must not complete successfully"); }
                    }));
            for (var error : new us.daconta.xlmeco.provider.SafeProviderFailure[]{sync, stream}) {
                assertEquals(429, error.httpStatus()); assertEquals(StructuredImageErrorCode.PROVIDER_RATE_LIMIT, error.code());
                assertTrue(error.safeMessage().contains(provider.getProviderName()));
                assertFalse(error.safeMessage().contains(SECRET)); assertNull(error.getCause());
            }
        }
    }
    @Test void safeFailureCannotCarryArbitraryStringsOrCause() {
        var error = new us.daconta.xlmeco.provider.SafeProviderFailure(SECRET, null, 123456);
        assertEquals("provider: PROVIDER_FAILURE HTTP 0", error.safeMessage());
        assertThrows(IllegalStateException.class, () -> error.initCause(new RuntimeException(SECRET)));
        assertEquals("provider", new us.daconta.xlmeco.provider.SafeProviderFailure(null, null, 0).provider());
    }
    @Test void embeddingUsesExplicitModel() {
        response = "{\"embedding\":{\"values\":[1,2]}}";
        GoogleProvider p = new GoogleProvider(); p.initialize(config());
        assertEquals(2, p.generateEmbedding("hello", ModelParameters.newBuilder().putParameters("model", "chosen").build()).size());
        assertEquals("models/chosen", new JSONObject(body.get()).getString("model"));
        response = "{\"data\":[{\"embedding\":[1,2]}]}";
        OpenAIProvider openai = new OpenAIProvider(); openai.initialize(config());
        assertEquals(2, openai.generateEmbedding("hello", ModelParameters.newBuilder().putParameters("model", "chosen").build()).size());
        assertEquals("chosen", new JSONObject(body.get()).getString("model"));
    }
}
