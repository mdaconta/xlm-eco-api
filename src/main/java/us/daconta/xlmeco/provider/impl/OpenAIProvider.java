package us.daconta.xlmeco.provider.impl;

import okhttp3.*;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.grpc.ModelParameters;
import us.daconta.xlmeco.provider.ChatProvider;
import io.grpc.stub.StreamObserver;
import org.json.JSONArray;
import org.json.JSONObject;
import us.daconta.xlmeco.provider.EmbeddingProvider;
import us.daconta.xlmeco.provider.GenerativeProvider;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Duration;
import org.json.JSONException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;

public class OpenAIProvider extends AbstractGenerativeProvider implements ChatProvider, EmbeddingProvider, StructuredImageProvider {
    public static final String VERSION = "1.0";
    public static final String PROVIDER_NAME = "openai";

    public static final String PROPERTY_API_KEY = GenerativeProvider.API_KEY;
    public static final String PROPERTY_URL_CHAT = GenerativeProvider.PROPERTY_URL_CHAT;
    public static final String PROPERTY_URL_EMBEDDING = GenerativeProvider.PROPERTY_URL_EMBEDDING;
    public static final String PROPERTY_DEFAULT_MODEL_LM = GenerativeProvider.PROPERTY_DEFAULT_MODEL_LM;
    public static final String PROPERTY_DEFAULT_MODEL_EMBEDDING = GenerativeProvider.PROPERTY_DEFAULT_MODEL_EMBEDDING;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final OkHttpClient visionHttpClient = httpClient.newBuilder().callTimeout(Duration.ofSeconds(60)).build();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private String apiKey;
    private String chatURL;
    private String embeddingURL;
    private String defaultLanguageModel;
    private String defaultEmbeddingModel;
    private Set<String> visionModels = Set.of("gpt-4o-mini");

    // Configuration Properties read from property file
    private Properties configProperties;

    public OpenAIProvider() {
        version = VERSION;
    }

    @Override
    public void initialize(Properties configProperties) {
        this.configProperties = configProperties;
        apiKey = configProperties.getProperty(PROPERTY_API_KEY);
        chatURL = configProperties.getProperty(PROPERTY_URL_CHAT);
        embeddingURL = configProperties.getProperty(PROPERTY_URL_EMBEDDING);
        defaultLanguageModel = configProperties.getProperty(PROPERTY_DEFAULT_MODEL_LM);
        defaultEmbeddingModel = configProperties.getProperty(PROPERTY_DEFAULT_MODEL_EMBEDDING);
        visionModels = Arrays.stream(configProperties.getProperty("vision_models", "gpt-4o-mini").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ServiceLevel getServiceLevel() {
        return ServiceLevel.LEVEL_2;  // OpenAI supports advanced features
    }

    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean supportsChat() {
        return true;
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public boolean supportsRAG() {
        return false;  // Assume for now that OpenAI doesn't support embeddings in this version
    }

    @Override
    public boolean supportsAgents() {
        return false;
    }

    @Override
    public boolean supportsStructuredImageModel(String model) {
        return visionModels.contains(model);
    }

    @Override
    public StructuredImageProvider.Result generateStructuredImage(StructuredImageProvider.Input input)
            throws StructuredImageProvider.Failure {
        JSONObject text = new JSONObject().put("type", "text").put("text", input.instructions());
        String dataUrl = "data:" + input.mimeType() + ";base64," + Base64.getEncoder().encodeToString(input.image());
        JSONObject image = new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUrl));
        JSONObject message = new JSONObject().put("role", "user")
                .put("content", new JSONArray().put(text).put(image));
        JSONObject format = new JSONObject().put("type", "json_schema")
                .put("json_schema", new JSONObject().put("name", "xlm_result")
                        .put("schema", new JSONObject(input.jsonSchema())).put("strict", true));
        JSONObject payload = new JSONObject().put("model", input.model())
                .put("messages", new JSONArray().put(message)).put("response_format", format);
        Request request = new Request.Builder().url(chatURL)
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey).build();
        try (Response response = visionHttpClient.newCall(request).execute()) {
            int status = response.code();
            if (!response.isSuccessful()) {
                StructuredImageErrorCode code;
                boolean retryable = false;
                if (status == 401 || status == 403) code = StructuredImageErrorCode.PROVIDER_AUTHENTICATION;
                else if (status == 429) {
                    String providerCode = "";
                    try {
                        JSONObject detail = new JSONObject(response.peekBody(16_384).string()).getJSONObject("error");
                        providerCode = detail.optString("code", detail.optString("type", ""));
                        if (providerCode.isEmpty()) providerCode = detail.optString("type", "");
                    } catch (JSONException ignored) { /* Keep safe HTTP classification. */ }
                    if (Set.of("insufficient_quota", "credit_balance_exhausted",
                            "organization_usage_limit_exceeded", "organization_spend_limit_exceeded",
                            "project_spend_limit_exceeded").contains(providerCode)) {
                        code = StructuredImageErrorCode.PROVIDER_QUOTA;
                    } else {
                        code = StructuredImageErrorCode.PROVIDER_RATE_LIMIT;
                        retryable = true;
                    }
                }
                else if (status == 408 || status == 504) { code = StructuredImageErrorCode.PROVIDER_TIMEOUT; retryable = true; }
                else if (status >= 500) { code = StructuredImageErrorCode.PROVIDER_UNAVAILABLE; retryable = true; }
                else code = StructuredImageErrorCode.PROVIDER_FAILURE;
                String safeMessage = code == StructuredImageErrorCode.PROVIDER_QUOTA
                        ? "Provider quota or spend limit reached" : "Provider returned HTTP " + status;
                throw new StructuredImageProvider.Failure(code, safeMessage, retryable, status);
            }
            if (response.body() == null) throw malformed("Provider returned an empty response");
            ResponseBody limited = response.peekBody(1_048_577);
            byte[] responseBytes = limited.bytes();
            if (responseBytes.length > 1_048_576) throw malformed("Provider response exceeds limit");
            JSONObject envelope;
            try {
                var parsed = JSON.readTree(responseBytes);
                if (parsed == null || !parsed.isObject()) throw malformed("Provider response is not a JSON object");
                envelope = new JSONObject(JSON.writeValueAsString(parsed));
            } catch (JsonProcessingException e) {
                throw malformed("Provider response is not valid JSON");
            }
            try {
                JSONObject choice = envelope.getJSONArray("choices").getJSONObject(0);
                JSONObject answer = choice.getJSONObject("message");
                if (!answer.isNull("refusal") && answer.optString("refusal").length() > 0)
                    throw new StructuredImageProvider.Failure(StructuredImageErrorCode.PROVIDER_FAILURE,
                            "Provider refused the request", false, status);
                if (!"stop".equals(choice.optString("finish_reason")))
                    throw malformed("Provider did not complete the response");
                String content = answer.getString("content");
                String normalized;
                try {
                    var parsed = JSON.readTree(content);
                    if (parsed == null || !parsed.isObject()) {
                        throw new StructuredImageProvider.Failure(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                                "Provider output is not a JSON object", false, status);
                    }
                    normalized = JSON.writeValueAsString(parsed);
                } catch (JsonProcessingException e) {
                    throw new StructuredImageProvider.Failure(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                            "Provider output is not a JSON object", false, status);
                }
                String actualModel = envelope.optString("model", input.model());
                return new StructuredImageProvider.Result(normalized, actualModel.isBlank() ? input.model() : actualModel);
            } catch (JSONException e) {
                throw malformed("Provider response is missing required fields");
            }
        } catch (java.net.SocketTimeoutException e) {
            throw new StructuredImageProvider.Failure(StructuredImageErrorCode.PROVIDER_TIMEOUT,
                    "Provider request timed out", true, 0);
        } catch (IOException e) {
            throw new StructuredImageProvider.Failure(StructuredImageErrorCode.PROVIDER_UNAVAILABLE,
                    "Provider connection failed", true, 0);
        }
    }

    private static StructuredImageProvider.Failure malformed(String message) {
        return new StructuredImageProvider.Failure(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                message, false, 0);
    }

    @Override
    public String generateChatResponse(ChatRequest request) throws IOException {
        String prompt = request.getPrompt();
        String modelName = request.getModelName();

        // Escape the prompt to make it JSON-safe
        String escapedPrompt = JSONObject.quote(prompt); // This will escape special characters in the prompt

        String jsonBody = "{ \"model\": \"" + modelName + "\", " +
                "\"messages\": [{ \"role\": \"system\", \"content\": \"You are a helpful assistant.\" }, " +
                "{ \"role\": \"user\", \"content\": " + escapedPrompt + " }], " +
                "\"max_tokens\": 1000 }";

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request httpRequest = new Request.Builder()
                .url(chatURL)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                return "Error: " + response.body().string();
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray choices = jsonResponse.getJSONArray("choices");
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

            return content.trim();
        }
    }

    @Override
    public void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws IOException {
        String prompt = request.getPrompt();
        // Escape the prompt to make it JSON-safe
        String escapedPrompt = JSONObject.quote(prompt); // This will escape special characters in the prompt

        String modelName = request.getModelName();

        String jsonBody = "{ \"model\": \"" + modelName + "\", " +
                "\"messages\": [{ \"role\": \"system\", \"content\": \"You are a helpful assistant.\" }, " +
                "{ \"role\": \"user\", \"content\": " + escapedPrompt + " }], " +
                "\"max_tokens\": 1000, \"stream\": true }";

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request httpRequest = new Request.Builder()
                .url(chatURL)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        Response response = httpClient.newCall(httpRequest).execute();
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data: ")) {
                String jsonData = line.substring(6);
                if ("[DONE]".equals(jsonData.trim())) {
                    break;
                }

                JSONObject jsonResponse = new JSONObject(jsonData);
                JSONArray choices = jsonResponse.getJSONArray("choices");
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");

                if (delta.has("content")) {
                    String token = delta.getString("content");
                    ChatResponsePart responsePart = ChatResponsePart.newBuilder().setToken(token).build();
                    responseObserver.onNext(responsePart);
                }
            }
        }
        responseObserver.onCompleted();
    }

    @Override
    public List<Float> generateEmbedding(String text, ModelParameters params) {
        // Create the JSON body for the request
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", defaultEmbeddingModel);
        jsonBody.put("input", text);

        // Build the HTTP request
        Request request = new Request.Builder()
                .url(embeddingURL)
                .post(RequestBody.create(jsonBody.toString(), MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        // Send the request and parse the response
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code: " + response);
            }

            // Parse the response to extract the embedding
            String responseBody = response.body().string();
            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray embeddingArray = responseJson.getJSONArray("data").getJSONObject(0).getJSONArray("embedding");

            List<Float> embedding = new ArrayList<>();
            for (int i = 0; i < embeddingArray.length(); i++) {
                embedding.add(embeddingArray.getFloat(i));
            }
            return embedding;
        } catch (IOException e) {
            throw new RuntimeException("Failed to get embedding from OpenAI: " + e.getMessage(), e);
        }
    }

}

