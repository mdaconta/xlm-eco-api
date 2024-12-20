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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class GrokProvider extends AbstractGenerativeProvider implements ChatProvider {
    public static final String VERSION = "1.0";
    public static final String PROVIDER_NAME = "grok";

    public static final String PROPERTY_API_KEY = GenerativeProvider.API_KEY;
    public static final String PROPERTY_URL_CHAT = GenerativeProvider.PROPERTY_URL_CHAT;
    public static final String PROPERTY_URL_EMBEDDING = GenerativeProvider.PROPERTY_URL_EMBEDDING;
    public static final String PROPERTY_DEFAULT_MODEL_LM = GenerativeProvider.PROPERTY_DEFAULT_MODEL_LM;
    public static final String PROPERTY_DEFAULT_MODEL_EMBEDDING = GenerativeProvider.PROPERTY_DEFAULT_MODEL_EMBEDDING;

    private final OkHttpClient httpClient = new OkHttpClient();
    private String apiKey;
    private String chatURL;
    private String embeddingURL;
    private String defaultLanguageModel;
    private String defaultEmbeddingModel;

    // Configuration Properties read from property file
    private Properties configProperties;

    public GrokProvider() {
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
    }

    @Override
    public ServiceLevel getServiceLevel() {
        return ServiceLevel.LEVEL_2;  // OpenAI supports advanced features
    }

    public String getProviderName() {
        return "grok";
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
        return false;  // Assume for now that OpenAI doesn't support embeddings in this version
    }

    @Override
    public boolean supportsAgents() {
        return false;
    }

    @Override
    public String generateChatResponse(ChatRequest request) throws IOException {
        String prompt = request.getPrompt();
        // Escape the prompt to make it JSON-safe
        String escapedPrompt = JSONObject.quote(prompt); // This will escape special characters in the prompt

        String modelName = request.getModelName();

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
                "{ \"role\": \"user\", \"content\":" + escapedPrompt + " }], " +
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
   

}