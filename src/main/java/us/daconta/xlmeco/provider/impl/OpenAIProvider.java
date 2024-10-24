package us.daconta.xlmeco.provider.impl;

import okhttp3.*;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import us.daconta.xlmeco.provider.ChatProvider;
import io.grpc.stub.StreamObserver;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class OpenAIProvider implements ChatProvider {

    private final OkHttpClient httpClient = new OkHttpClient();

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
        return false;  // Assume for now that OpenAI doesn't support embeddings in this version
    }

    @Override
    public boolean supportsRAG() {
        return false;  // Assume for now that OpenAI doesn't support embeddings in this version
    }

    @Override
    public String generateChatResponse(ChatRequest request) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String prompt = request.getPrompt();
        String modelName = request.getModelName();

        String jsonBody = "{ \"model\": \"" + modelName + "\", " +
                "\"messages\": [{ \"role\": \"system\", \"content\": \"You are a helpful assistant.\" }, " +
                "{ \"role\": \"user\", \"content\": \"" + prompt + "\" }], " +
                "\"max_tokens\": 1000 }";

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request httpRequest = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
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
        String apiKey = System.getenv("OPENAI_API_KEY");
        String prompt = request.getPrompt();
        String modelName = request.getModelName();

        String jsonBody = "{ \"model\": \"" + modelName + "\", " +
                "\"messages\": [{ \"role\": \"system\", \"content\": \"You are a helpful assistant.\" }, " +
                "{ \"role\": \"user\", \"content\": \"" + prompt + "\" }], " +
                "\"max_tokens\": 1000, \"stream\": true }";

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Request httpRequest = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
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

