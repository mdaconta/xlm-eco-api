package us.daconta.xlmeco;

import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.XlmEcosystemServiceGrpc;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponse;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import java.io.IOException;

public class XlmEcosystemServiceImpl extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {

    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public void chat(ChatRequest request, StreamObserver<ChatResponse> responseObserver) {
        String prompt = request.getPrompt();
        String model = request.getModel();

        String completion = "";
        try {
            if ("openai".equalsIgnoreCase(model)) {
                completion = callOpenAI(prompt);
            } else if ("anthropic".equalsIgnoreCase(model)) {
                completion = callAnthropic(prompt);
            } else if ("gemini".equalsIgnoreCase(model)) {
                completion = callGemini(prompt);
            } else {
                completion = "Model not supported";
            }
        } catch (IOException e) {
            e.printStackTrace();
            completion = "Error processing request";
        }

        // Build and send response
        ChatResponse response = ChatResponse.newBuilder().setCompletion(completion).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private String callOpenAI(String prompt) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");

        // Define the JSON body with the 'messages' parameter
        String jsonBody = "{\n" +
                "  \"model\": \"gpt-4o-mini\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},\n" +
                "    {\"role\": \"user\", \"content\": \"" + prompt + "\"}\n" +
                "  ],\n" +
                "  \"max_tokens\": 1000\n" +
                "}";

        // Build the request
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), jsonBody);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        // Send the request and capture the response
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error: " + response.body().string();
            }
            return response.body().string();  // Return the response body
        }
    }

    private String callAnthropic(String prompt) throws IOException {
        String apiKey = "your_anthropic_api_key";
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                "{\"model\":\"claude-2\",\"prompt\":\"" + prompt + "\",\"max_tokens\":1000}"
        );

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/complete")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.body().string();
        }
    }

    private String callGemini(String prompt) throws IOException {

        String projectId = System.getenv("GEMINI_PROJECT_ID");;
        String location = "us-central1";
        String modelName = "gemini-1.5-flash-001";

        String output = textInput(projectId, location, modelName, prompt);

        return output;
    }

    // Passes the provided text input to the Gemini model and returns the text-only response.
    public String textInput(
            String projectId, String location, String modelName, String textPrompt) throws IOException {
        // Initialize client that will be used to send requests. This client only needs
        // to be created once, and can be reused for multiple requests.
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            GenerativeModel model = new GenerativeModel(modelName, vertexAI);

            GenerateContentResponse response = model.generateContent(textPrompt);
            String output = ResponseHandler.getText(response);
            return output;
        }
    }

}

