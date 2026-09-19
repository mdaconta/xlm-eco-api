package us.daconta.xlmeco;

import com.sun.net.httpserver.HttpServer;
import io.grpc.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.json.*;
import us.daconta.xlmeco.admin.*;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import us.daconta.xlmeco.security.ExternalSecrets;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationServiceTest {
    HttpServer http; Server server; ManagedChannel channel;
    XlmEcosystemServiceGrpc.XlmEcosystemServiceBlockingStub stub;
    ProviderRegistry registry;
    byte[] png; String body; int calls;
    @BeforeEach void start() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        png = bytes.toByteArray();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/image", exchange -> {
            calls++;
            body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            byte[] response = new JSONObject().put("data", new JSONArray().put(new JSONObject()
                    .put("b64_json", Base64.getEncoder().encodeToString(png)))).toString().getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        http.start();
        var properties = new Properties();
        properties.setProperty("openai.api_key", "synthetic-test-marker");
        properties.setProperty("openai.image_url", "http://127.0.0.1:" + http.getAddress().getPort() + "/image");
        registry = ProviderRegistry.inMemory(properties);
        server = ServerBuilder.forPort(0).addService(new XlmEcosystemServiceImpl(registry)).addService(new AdminService(registry)).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        stub = XlmEcosystemServiceGrpc.newBlockingStub(channel);
        stub.registerClient(ClientRegistrationRequest.newBuilder().setClientId("test").build());
    }
    @AfterEach void stop() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        if (http != null) http.stop(0);
        if (registry != null) registry.close();
    }
    ImageGenerationRequest.Builder request() {
        return ImageGenerationRequest.newBuilder().setClientId("test").setProvider("openai")
                .setModel("gpt-image-1").setPrompt("Draw a blue circle");
    }
    @Test void dispatchesExplicitOperationAndNormalizesImage() {
        var response = stub.generateImage(request().build());
        assertTrue(response.getSuccess(), response.toString());
        assertEquals(GeneratedOutputType.GENERATED_IMAGE, response.getOutputType());
        assertEquals("image/png", response.getImage().getMimeType());
        assertArrayEquals(png, response.getImage().getData().toByteArray());
        assertEquals("gpt-image-1", response.getModel());
        assertEquals("openai", response.getProvider());
        assertFalse(response.getRequestId().isBlank());
        assertNotEquals(response.getRequestId(), stub.generateImage(request().build()).getRequestId());
        assertEquals("gpt-image-1", new JSONObject(body).getString("model"));
        assertFalse(response.toString().contains("synthetic-test-marker"));
    }
    @Test void rejectsWrongCapabilityAndInvalidRequestsWithoutHttp() {
        assertCode(request().setModel("gpt-4o-mini"), StructuredImageErrorCode.INCOMPATIBLE_CAPABILITY);
        assertCode(request().setProvider("anthropic").setModel("claude-sonnet-4-6"), StructuredImageErrorCode.INCOMPATIBLE_CAPABILITY);
        assertCode(request().setModel("missing"), StructuredImageErrorCode.UNSUPPORTED_MODEL);
        assertCode(request().setProvider("missing"), StructuredImageErrorCode.UNSUPPORTED_PROVIDER);
        assertCode(request().setClientId("unregistered"), StructuredImageErrorCode.INVALID_REQUEST);
        assertCode(request().clearPrompt(), StructuredImageErrorCode.INVALID_REQUEST);
        assertCode(request().setPrompt("é".repeat(32769)), StructuredImageErrorCode.INVALID_REQUEST);
        assertCode(request().clearProvider(), StructuredImageErrorCode.INVALID_REQUEST);
        assertCode(request().clearProvider().clearModel(), StructuredImageErrorCode.INVALID_REQUEST);
        assertEquals(0, calls);
    }
    private void assertCode(ImageGenerationRequest.Builder request, StructuredImageErrorCode code) {
        var result = stub.generateImage(request.build());
        assertFalse(result.getSuccess()); assertFalse(result.hasImage());
        assertEquals(GeneratedOutputType.GENERATED_OUTPUT_UNSPECIFIED, result.getOutputType());
        assertEquals(code, result.getError().getCode());
    }
    @Test void explicitSelectionDefaultsAndDisablementStayAuthoritative() {
        registry.setDefault(registry.snapshot().state().revision(), "image_generation", new ProviderRegistry.Selection("openai", "gpt-image-1"));
        assertTrue(stub.generateImage(request().clearProvider().clearModel().build()).getSuccess());
        assertTrue(stub.generateImage(request().clearModel().build()).getSuccess());
        assertCode(request().setProvider("google").clearModel(), StructuredImageErrorCode.INVALID_REQUEST);
        registry.setDefault(registry.snapshot().state().revision(), "image_generation", null);
        registry.upsertModel(registry.snapshot().state().revision(), new ProviderRegistry.Model("openai", "gpt-image-1", false, Set.of("image_generation"), "Image"));
        assertCode(request(), StructuredImageErrorCode.MODEL_DISABLED);
        registry.updateProvider(registry.snapshot().state().revision(), "anthropic", false, Map.of());
        assertCode(request().setProvider("anthropic").setModel("claude-sonnet-4-6"), StructuredImageErrorCode.PROVIDER_DISABLED);
    }
    @Test void discoveryAndAdminExposeIndependentCapabilities() {
        var models = stub.listModels(ModelCatalogRequest.newBuilder().setClientId("test").setCapability("image_generation").build());
        assertEquals(2, models.getModelsCount());
        assertTrue(models.getModelsList().stream().allMatch(m -> m.getEnabled() && m.getCapabilitiesList().equals(List.of("image_generation"))));
        var admin = XlmAdminServiceGrpc.newBlockingStub(channel);
        assertEquals(2, admin.listModels(AdminListModelsRequest.newBuilder().setCapability("image_generation").build()).getModelsCount());
        var selected = stub.listModels(ModelCatalogRequest.newBuilder().setClientId("test").setProvider("openai").build());
        assertTrue(selected.getModelsList().stream().allMatch(m -> m.getProvider().equals("openai")));
        assertThrows(StatusRuntimeException.class, () -> stub.listModels(ModelCatalogRequest.getDefaultInstance()));
        assertThrows(StatusRuntimeException.class, () -> stub.listModels(ModelCatalogRequest.newBuilder().setClientId("test").setCapability("bad").build()));
        assertThrows(StatusRuntimeException.class, () -> stub.listModels(ModelCatalogRequest.newBuilder().setClientId("test").setProvider("bad").build()));
        assertTrue(stub.getProviderCapabilities(ProviderRequest.newBuilder().setClientId("test").setProvider("openai").build()).getCapabilitiesOrThrow("image_generation"));
    }
    @Test void generationRequiresCredentialsEvenWhenGoogleAdcIsConfigured() {
        registry.updateProvider(registry.snapshot().state().revision(), "google", null, Map.of("project_id", "test", "location", "test"));
        assertCode(request().setProvider("google").setModel("gemini-2.5-flash-image"), StructuredImageErrorCode.PROVIDER_AUTHENTICATION);
    }
    @Test void pythonClientCrossesActualJavaGrpcBoundary() throws Exception {
        var process = new ProcessBuilder("python", "src/test/python/image_generation_bridge_check.py", Integer.toString(server.getPort()))
                .redirectErrorStream(true).start();
        boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Python bridge timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("PASS"), output);
    }
    @Test void capabilitiesAndDefaultPersistWithoutReseeding(@TempDir Path path) throws Exception {
        // No credentials are needed for metadata persistence.
        try (var first = new ProviderRegistry(path, null, new Properties())) {
            first.upsertModel(first.snapshot().state().revision(), new ProviderRegistry.Model("openai", "both-test", true, Set.of("structured_image", "image_generation"), "Both"));
            first.setDefault(first.snapshot().state().revision(), "image_generation", new ProviderRegistry.Selection("openai", "both-test"));
        }
        try (var reopened = new ProviderRegistry(path, null, new Properties())) {
            var state = reopened.snapshot().state();
            assertEquals(Set.of("structured_image", "image_generation"), state.models().get("openai/both-test").capabilities());
            assertEquals("both-test", state.defaults().get("image_generation").model());
            assertFalse(state.models().get("openai/gpt-4o-mini").capabilities().contains("image_generation"));
        }
    }
}
