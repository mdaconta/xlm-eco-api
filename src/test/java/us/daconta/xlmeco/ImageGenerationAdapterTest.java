package us.daconta.xlmeco;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;
import us.daconta.xlmeco.provider.ImageGenerationProvider;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import us.daconta.xlmeco.provider.impl.GoogleProvider;
import us.daconta.xlmeco.provider.impl.OpenAIProvider;
import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationAdapterTest {
    static final ImageGenerationProvider.Input INPUT = new ImageGenerationProvider.Input("Draw a blue square", "test-image-model");
    static String image(String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static JSONObject inline(String mime, String data) {
        return new JSONObject().put("inlineData", new JSONObject().put("mimeType", mime).put("data", data));
    }
    static JSONObject google(JSONArray parts) {
        return new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                .put("content", new JSONObject().put("parts", parts))));
    }
    static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<String> body = new AtomicReference<>("{}");
        final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
        JSONObject request;
        String auth, key, query;
        Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth = exchange.getRequestHeaders().getFirst("Authorization");
                key = exchange.getRequestHeaders().getFirst("x-goog-api-key");
                query = exchange.getRequestURI().getQuery();
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", url());
                exchange.sendResponseHeaders(status.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
        Properties properties() {
            Properties p = new Properties(); p.setProperty("api_key", "SYNTHETIC_SECRET");
            p.setProperty("chat_url", url()); p.setProperty("image_url", url()); return p;
        }
        ImageGenerationProvider provider(boolean openai) {
            if (openai) { var p = new OpenAIProvider(); p.initialize(properties()); return p; }
            var p = new GoogleProvider(); p.initialize(properties()); return p;
        }
        public void close() { server.stop(0); }
    }
    @Test void openaiPayloadAndValidatedImageWithModelFallback() throws Exception {
        try (var e = new Endpoint()) {
            String encoded = image("png");
            e.body.set(new JSONObject().put("data", new JSONArray().put(new JSONObject().put("b64_json", encoded))).toString());
            var p = e.provider(true); var result = p.generateImage(INPUT);
            assertEquals("image/png", result.mimeType()); assertArrayEquals(Base64.getDecoder().decode(encoded), result.data());
            assertEquals(INPUT.model(), result.model()); assertEquals("Bearer SYNTHETIC_SECRET", e.auth);
            assertEquals(INPUT.prompt(), e.request.getString("prompt")); assertEquals(INPUT.model(), e.request.getString("model"));
            assertEquals(1, e.request.getInt("n")); assertEquals("png", e.request.getString("output_format"));
            assertEquals("1024x1024", e.request.getString("size")); assertEquals("low", e.request.getString("quality"));
            e.body.set(new JSONObject(e.body.get()).put("model", "actual-model").toString());
            assertEquals("actual-model", p.generateImage(INPUT).model());
            e.body.set(new JSONObject(e.body.get()).put("model", "").toString());
            assertEquals(INPUT.model(), p.generateImage(INPUT).model());
        }
    }
    @Test void googlePayloadSkipsThoughtAndTextAndReturnsJpeg() throws Exception {
        try (var e = new Endpoint()) {
            String encoded = image("jpeg");
            e.body.set(google(new JSONArray().put(inline("image/png", "ignored").put("thought", true))
                    .put(new JSONObject().put("text", "description")).put(inline("image/jpeg", encoded)))
                    .put("modelVersion", "actual-google").toString());
            var p = e.provider(false); var result = p.generateImage(INPUT);
            assertEquals("image/jpeg", result.mimeType()); assertArrayEquals(Base64.getDecoder().decode(encoded), result.data());
            assertEquals("actual-google", result.model()); assertEquals("SYNTHETIC_SECRET", e.key); assertNull(e.auth); assertNull(e.query);
            JSONObject config = e.request.getJSONObject("generationConfig");
            assertEquals("[\"TEXT\",\"IMAGE\"]", config.getJSONArray("responseModalities").toString());
            assertFalse(config.has("responseJsonSchema")); assertFalse(config.has("responseMimeType"));
            assertEquals(INPUT.prompt(), e.request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));
            for (String model : new String[]{"", INPUT.model()}) {
                e.body.set(google(new JSONArray().put(inline("image/png", image("png")))).put("modelVersion", model).toString());
                assertEquals(INPUT.model(), p.generateImage(INPUT).model());
            }
        }
    }
    @Test void malformedImagesAndEnvelopesFailClosed() throws Exception {
        try (var e = new Endpoint()) {
            for (boolean openai : new boolean[]{true, false}) {
                var p = e.provider(openai);
                for (String body : new String[]{"[]", "{}", "{} {}", "not json", "{\"pad\":\"" + "x".repeat(4 * 1024 * 1024 + 65536) + "\"}"}) {
                    e.body.set(body); assertEquals(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                            assertThrows(StructuredImageProvider.Failure.class, () -> p.generateImage(INPUT)).code());
                }
            }
            var openai = e.provider(true);
            for (JSONArray images : new JSONArray[]{new JSONArray(), new JSONArray().put(new JSONObject().put("url", "https://invalid/")),
                    new JSONArray().put(new JSONObject().put("b64_json", "!")), new JSONArray().put(new JSONObject().put("b64_json", image("jpeg"))),
                    new JSONArray().put(new JSONObject()).put(new JSONObject())}) {
                e.body.set(new JSONObject().put("data", images).toString());
                assertThrows(StructuredImageProvider.Failure.class, () -> openai.generateImage(INPUT));
            }
            var google = e.provider(false);
            for (JSONArray parts : new JSONArray[]{new JSONArray(), new JSONArray().put(new JSONObject().put("text", "No image")),
                    new JSONArray().put(inline("image/gif", image("png"))), new JSONArray().put(inline("image/png", "not-base64")),
                    new JSONArray().put(inline("image/png", image("png"))).put(inline("image/png", image("png")))}) {
                e.body.set(google(parts).toString()); assertThrows(StructuredImageProvider.Failure.class, () -> google.generateImage(INPUT));
            }
            JSONObject stopped = google(new JSONArray().put(inline("image/png", image("png"))));
            stopped.getJSONArray("candidates").getJSONObject(0).put("finishReason", "SAFETY");
            e.body.set(stopped.toString()); assertThrows(StructuredImageProvider.Failure.class, () -> google.generateImage(INPUT));
            e.body.set(new JSONObject().put("candidates", new JSONArray()).toString());
            assertThrows(StructuredImageProvider.Failure.class, () -> google.generateImage(INPUT));
        }
    }
    @Test void safeHttpErrorsNeverRetryRedirectOrLeakProviderBody() throws Exception {
        try (var e = new Endpoint()) {
            for (boolean openai : new boolean[]{true, false}) {
                var p = e.provider(openai);
                for (int status : new int[]{302, 400, 401, 403, 408, 429, 500, 504}) {
                    e.status.set(status); e.body.set("SYNTHETIC_SECRET provider private detail"); int before = e.calls.get();
                    var failure = assertThrows(StructuredImageProvider.Failure.class, () -> p.generateImage(INPUT));
                    assertEquals(status, failure.httpStatus()); assertEquals(before + 1, e.calls.get());
                    assertFalse(failure.getMessage().contains("SYNTHETIC")); assertNull(failure.getCause());
                }
                e.status.set(429); e.body.set("{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"SYNTHETIC_SECRET\"}}");
                var quota = assertThrows(StructuredImageProvider.Failure.class, () -> p.generateImage(INPUT));
                assertEquals(StructuredImageErrorCode.PROVIDER_QUOTA, quota.code()); assertFalse(quota.retryable());
            }
        }
    }
}
