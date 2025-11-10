package us.daconta.xlmeco.provider.impl;

import io.grpc.stub.StreamObserver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.grpc.LmParameters;
import us.daconta.xlmeco.provider.ChatProvider;
import us.daconta.xlmeco.provider.GenerativeProvider;

/**
 * Provider implementation for interacting with local Ollama language models.
 */
public class OllamaProvider extends AbstractGenerativeProvider implements ChatProvider {

    public static final String PROVIDER_NAME = "ollama";
    public static final String VERSION = "1.0";

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();
    private String apiKey;
    private String chatURL;
    private String defaultLanguageModel;

    public OllamaProvider() {
        version = VERSION;
    }

    @Override
    public void initialize(Properties configProperties) {
        this.apiKey = configProperties.getProperty(GenerativeProvider.API_KEY);
        this.chatURL = configProperties.getProperty(GenerativeProvider.PROPERTY_URL_CHAT, "http://localhost:11434/api/chat");
        this.defaultLanguageModel = configProperties.getProperty(GenerativeProvider.PROPERTY_DEFAULT_MODEL_LM, "llama3");
    }

    @Override
    public String generateChatResponse(ChatRequest request) throws IOException {
        String modelName = resolveModelName(request);

        JSONObject payload = buildChatPayload(request, modelName, false);
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request httpRequest = buildRequest(body);

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                return "Error: " + errorBody;
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            if (responseBody.isEmpty()) {
                return "";
            }

            JSONObject jsonResponse = new JSONObject(responseBody);
            if (jsonResponse.has("message")) {
                JSONObject message = jsonResponse.getJSONObject("message");
                return message.optString("content", "").trim();
            }
            return jsonResponse.optString("response", "").trim();
        }
    }

    @Override
    public void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws Exception {
        String modelName = resolveModelName(request);

        JSONObject payload = buildChatPayload(request, modelName, true);
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request httpRequest = buildRequest(body);

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                responseObserver.onError(new IOException("Error: " + errorBody));
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    JSONObject jsonChunk = new JSONObject(line);
                    if (jsonChunk.optBoolean("done", false) && !jsonChunk.has("message")) {
                        break;
                    }

                    if (jsonChunk.has("message")) {
                        JSONObject message = jsonChunk.getJSONObject("message");
                        String token = message.optString("content", "");
                        if (!token.isEmpty()) {
                            responseObserver.onNext(ChatResponsePart.newBuilder().setToken(token).build());
                        }
                    } else if (jsonChunk.has("response")) {
                        String token = jsonChunk.optString("response", "");
                        if (!token.isEmpty()) {
                            responseObserver.onNext(ChatResponsePart.newBuilder().setToken(token).build());
                        }
                    }

                    if (jsonChunk.optBoolean("done", false)) {
                        break;
                    }
                }
            }
            responseObserver.onCompleted();
        }
    }

    private JSONObject buildChatPayload(ChatRequest request, String modelName, boolean stream) {
        JSONObject payload = new JSONObject();
        payload.put("model", modelName);
        payload.put("stream", stream);

        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", request.getPrompt());
        messages.put(userMessage);
        payload.put("messages", messages);

        JSONObject options = buildOptions(request.getParams());
        if (options.length() > 0) {
            payload.put("options", options);
        }

        return payload;
    }

    private JSONObject buildOptions(LmParameters params) {
        JSONObject options = new JSONObject();
        if (params == null) {
            return options;
        }
        if (params.getMaxTokens() > 0) {
            options.put("num_predict", params.getMaxTokens());
        }
        if (params.getTemperature() != 0.0f) {
            options.put("temperature", params.getTemperature());
        }
        if (params.getFrequencyPenalty() != 0.0f) {
            options.put("repeat_penalty", params.getFrequencyPenalty());
        }
        if (params.getPresencePenalty() != 0.0f) {
            options.put("presence_penalty", params.getPresencePenalty());
        }
        return options;
    }

    private Request buildRequest(RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(chatURL)
                .post(body)
                .addHeader("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private String resolveModelName(ChatRequest request) {
        String requestedModel = request.getModelName();
        if (requestedModel == null || requestedModel.isBlank()) {
            requestedModel = defaultLanguageModel;
        }
        return requestedModel;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public ServiceLevel getServiceLevel() {
        return ServiceLevel.LEVEL_1;
    }

    @Override
    public boolean supportsChat() {
        return true;
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public boolean supportsRAG() {
        return false;
    }

    @Override
    public boolean supportsAgents() {
        return false;
    }
}
