package us.daconta.xlmeco.provider.impl;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.cloud.aiplatform.v1.*;
import com.google.protobuf.Value;
import io.grpc.stub.StreamObserver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import okhttp3.HttpUrl;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import java.io.IOException;
import java.util.*;

public class GoogleProvider extends AbstractGenerativeProvider implements ChatProvider, EmbeddingProvider, StructuredImageProvider, ImageGenerationProvider {
    public static final String VERSION = "1.0", PROVIDER_NAME = "google";
    public static final String PROJECT_ID = "project_id", LOCATION = "location";
    public static final String PROPERTY_LOCATION = LOCATION, PROPERTY_PROJECT_ID = PROJECT_ID;
    public static final String PROPERTY_API_KEY = GenerativeProvider.API_KEY;
    public static final String PROPERTY_URL_CHAT = GenerativeProvider.PROPERTY_URL_CHAT;
    public static final String PROPERTY_URL_EMBEDDING = GenerativeProvider.PROPERTY_URL_EMBEDDING;
    public static final String PROPERTY_DEFAULT_MODEL_LM = GenerativeProvider.PROPERTY_DEFAULT_MODEL_LM;
    public static final String PROPERTY_DEFAULT_MODEL_EMBEDDING = GenerativeProvider.PROPERTY_DEFAULT_MODEL_EMBEDDING;
    private String apiKey, projectId, location, chatURL, embeddingURL, defaultLmModel, defaultEmbeddingModel;
    private ProviderHttp http;
    private int outputTokens;
    public void initialize(Properties properties) {
        apiKey = properties.getProperty(PROPERTY_API_KEY);
        projectId = properties.getProperty(PROPERTY_PROJECT_ID);
        location = properties.getProperty(PROPERTY_LOCATION);
        chatURL = properties.getProperty(PROPERTY_URL_CHAT);
        embeddingURL = properties.getProperty(PROPERTY_URL_EMBEDDING);
        defaultLmModel = properties.getProperty(PROPERTY_DEFAULT_MODEL_LM);
        defaultEmbeddingModel = properties.getProperty(PROPERTY_DEFAULT_MODEL_EMBEDDING);
        outputTokens = Integer.parseInt(properties.getProperty("output_tokens", "4096"));
        http = new ProviderHttp(properties);
    }
    private String endpoint(String override, String model, String operation) {
        if (override != null && !override.isBlank()) {
            if (override.endsWith("/models/")) return HttpUrl.parse(override).newBuilder()
                    .addPathSegment(model + ":" + operation).build().toString();
            return override;
        }
        return new HttpUrl.Builder().scheme("https").host("generativelanguage.googleapis.com")
                .addPathSegment("v1beta").addPathSegment("models").addPathSegment(model + ":" + operation).build().toString();
    }
    private JSONObject contents(JSONArray parts) {
        return new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
    }
    private String answer(JSONObject envelope) throws Failure {
        try {
            JSONObject candidate = envelope.getJSONArray("candidates").getJSONObject(0);
            if (!"STOP".equals(candidate.optString("finishReason"))) throw ProviderHttp.malformed();
            JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (!part.optBoolean("thought", false) && part.has("text")) output.append(part.getString("text"));
            }
            if (output.isEmpty()) throw ProviderHttp.malformed();
            return output.toString();
        } catch (JSONException e) { throw ProviderHttp.malformed(); }
    }
    public StructuredImageProvider.Result generateStructuredImage(StructuredImageProvider.Input input) throws Failure {
        try {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", input.instructions()))
                    .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", input.mimeType())
                            .put("data", Base64.getEncoder().encodeToString(input.image()))));
            JSONObject payload = contents(parts).put("generationConfig", new JSONObject()
                    .put("responseMimeType", "application/json").put("responseJsonSchema", new JSONObject(input.jsonSchema()))
                    .put("maxOutputTokens", outputTokens));
            JSONObject envelope = http.post(endpoint(chatURL, input.model(), "generateContent"), "x-goog-api-key", apiKey, payload, false);
            return new StructuredImageProvider.Result(ProviderHttp.structured(answer(envelope)), envelope.optString("modelVersion", input.model()));
        } catch (JSONException | IllegalArgumentException e) { throw ProviderHttp.malformed(); }
    }
    public boolean supportsStructuredImageModel(String model) { return model != null && !model.isBlank(); }
    @Override
    public ImageGenerationProvider.Result generateImage(ImageGenerationProvider.Input input) throws Failure {
        JSONObject payload = contents(new JSONArray().put(new JSONObject().put("text", input.prompt())))
                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("TEXT").put("IMAGE")));
        JSONObject envelope = http.post(endpoint(chatURL, input.model(), "generateContent"), "x-goog-api-key", apiKey,
                payload, false, ProviderHttp.IMAGE_ENVELOPE_BYTES);
        try {
            JSONArray candidates = envelope.getJSONArray("candidates");
            if (candidates.length() != 1) throw ProviderHttp.malformed();
            JSONObject candidate = candidates.getJSONObject(0);
            if (!"STOP".equals(candidate.optString("finishReason"))) throw ProviderHttp.malformed();
            JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
            JSONObject image = null;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (!part.optBoolean("thought", false) && part.has("inlineData")) {
                    if (image != null) throw ProviderHttp.malformed();
                    image = part.getJSONObject("inlineData");
                }
            }
            if (image == null) throw ProviderHttp.malformed();
            String mime = image.getString("mimeType");
            byte[] bytes = GeneratedImageValidator.decode(mime, image.getString("data"));
            String actualModel = envelope.optString("modelVersion", input.model());
            return new ImageGenerationProvider.Result(mime, bytes, actualModel.isBlank() ? input.model() : actualModel);
        } catch (JSONException e) { throw ProviderHttp.malformed(); }
    }
    public String generateChatResponse(ChatRequest request) throws Exception {
        String model = request.getModelName().isBlank() ? defaultLmModel : request.getModelName();
        if (apiKey == null || apiKey.isBlank()) return textInput(projectId, location, model, request.getPrompt());
        try {
            return answer(http.post(endpoint(chatURL, model, "generateContent"), "x-goog-api-key", apiKey,
                    contents(new JSONArray().put(new JSONObject().put("text", request.getPrompt()))), false));
        } catch (Failure e) { throw ProviderHttp.safeTextFailure(PROVIDER_NAME, e); }
        catch (RuntimeException e) { throw new SafeProviderFailure(PROVIDER_NAME, StructuredImageErrorCode.PROVIDER_FAILURE, 0); }
    }
    public String textInput(String projectId, String location, String modelName, String textPrompt) throws IOException {
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            return ResponseHandler.getText(new GenerativeModel(modelName, vertexAI).generateContent(textPrompt));
        } catch (Exception e) { throw new SafeProviderFailure(PROVIDER_NAME, StructuredImageErrorCode.PROVIDER_FAILURE, 0); }
    }
    public void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> observer) throws Exception {
        // The REST path returns one bounded completion part; the gRPC stream contract is retained.
        if (apiKey != null && !apiKey.isBlank()) {
            observer.onNext(ChatResponsePart.newBuilder().setToken(generateChatResponse(request)).build());
            observer.onCompleted();
            return;
        }
        String model = request.getModelName().isBlank() ? defaultLmModel : request.getModelName();
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            new GenerativeModel(model, vertexAI).generateContentStream(request.getPrompt()).stream()
                    .forEach(response -> observer.onNext(ChatResponsePart.newBuilder().setToken(ResponseHandler.getText(response)).build()));
            observer.onCompleted();
        } catch (Exception e) { throw new SafeProviderFailure(PROVIDER_NAME, StructuredImageErrorCode.PROVIDER_FAILURE, 0); }
    }
    public List<Float> generateEmbedding(String text, ModelParameters params) {
        String model = params.getParametersOrDefault("model", defaultEmbeddingModel);
        try {
            List<Float> values = new ArrayList<>();
            if (apiKey != null && !apiKey.isBlank()) {
                JSONObject payload = new JSONObject().put("model", "models/" + model).put("content",
                        new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", text))));
                JSONArray data = http.post(endpoint(embeddingURL, model, "embedContent"), "x-goog-api-key", apiKey, payload, false)
                        .getJSONObject("embedding").getJSONArray("values");
                for (int i = 0; i < data.length(); i++) values.add(data.getFloat(i));
            } else {
                PredictionServiceSettings settings = PredictionServiceSettings.newBuilder()
                        .setEndpoint(location + "-aiplatform.googleapis.com:443").build();
                try (PredictionServiceClient client = PredictionServiceClient.create(settings)) {
                    Value instance = Value.newBuilder().setStructValue(com.google.protobuf.Struct.newBuilder()
                            .putFields("content", Value.newBuilder().setStringValue(text).build())).build();
                    PredictResponse result = client.predict(PredictRequest.newBuilder().setEndpoint(
                            EndpointName.ofProjectLocationPublisherModelName(projectId, location, "google", model).toString())
                            .addInstances(instance).build());
                    for (Value prediction : result.getPredictionsList()) {
                        var vector = prediction.getStructValue().getFieldsOrThrow("embeddings").getStructValue()
                                .getFieldsOrThrow("values").getListValue();
                        for (Value value : vector.getValuesList()) values.add((float) value.getNumberValue());
                    }
                }
            }
            return values;
        } catch (Failure e) { throw ProviderHttp.safeTextFailure(PROVIDER_NAME, e); }
        catch (Exception e) { throw new SafeProviderFailure(PROVIDER_NAME, StructuredImageErrorCode.PROVIDER_FAILURE, 0); }
    }
    public String getProviderName() { return PROVIDER_NAME; }
    public boolean supportsChat() { return true; }
    public boolean supportsEmbeddings() { return true; }
    public boolean supportsRAG() { return false; }
    public boolean supportsAgents() { return false; }
}
