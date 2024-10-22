package us.daconta.xlmeco;

import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.Part;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.grpc.XlmEcosystemServiceGrpc;
import us.daconta.xlmeco.grpc.ChatRequest;
import us.daconta.xlmeco.grpc.ChatResponse;
import us.daconta.xlmeco.grpc.ChatResponsePart;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import org.json.JSONObject;
import org.json.JSONArray;

public class XlmEcosystemServiceImpl extends XlmEcosystemServiceGrpc.XlmEcosystemServiceImplBase {

    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public void syncChat(ChatRequest request, StreamObserver<ChatResponse> responseObserver) {
        String prompt = request.getPrompt();
        String provider = request.getProvider();
        String modelName = request.getModelName();

        String completion = "";
        try {
            if ("openai".equalsIgnoreCase(provider)) {
                completion = callOpenAI(prompt, modelName);
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                completion = callAnthropic(prompt, modelName);
            } else if ("gemini".equalsIgnoreCase(provider)) {
                completion = callGemini(prompt, modelName);
            } else {
                completion = "Provider not supported";
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

    private String callOpenAI(String prompt, String modelName) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");

        // Define the JSON body with the 'messages' parameter
        String jsonBody = "{\n" +
                "  \"model\": " + "\"" + modelName + "\",\n" +
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


            // Parse the response to extract the 'content' field
            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray choices = jsonResponse.getJSONArray("choices");
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

            return content.trim();  // Return only the content field
        }
    }

    private String callAnthropic(String prompt, String modelName) throws IOException {
        String apiKey = "your_anthropic_api_key";
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                "{\"model\":" + "\"" + modelName + "\"" + ",\"prompt\":\"" + prompt + "\",\"max_tokens\":1000}"
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

    private String callGemini(String prompt, String modelName) throws IOException {

        String projectId = System.getenv("GEMINI_PROJECT_ID");

        // TBD: Fix this location as part of the provider config?
        String location = "us-central1";

        if (modelName == null || modelName.isEmpty())
            modelName = "gemini-1.5-flash-001"; // default

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

    @Override
    public void asyncChat(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) {
        String prompt = request.getPrompt();
        String provider = request.getProvider();
        String modelName = request.getModelName();

        try {
            if ("openai".equalsIgnoreCase(provider)) {
                streamOpenAI(prompt, modelName, responseObserver);
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                streamAnthropic(prompt, modelName, responseObserver);
            } else if ("gemini".equalsIgnoreCase(provider)) {
                streamGemini(prompt, modelName, responseObserver);
            } else {
                responseObserver.onError(new IllegalArgumentException("Provider not supported"));
            }
        } catch (IOException e) {
            e.printStackTrace();
            responseObserver.onError(new RuntimeException("Error processing request"));
        } finally {
            responseObserver.onCompleted();
        }
    }

    private void streamOpenAI(String prompt, String modelName, StreamObserver<ChatResponsePart> responseObserver) throws IOException {
        String apiKey = System.getenv("OPENAI_API_KEY");

        String jsonBody = "{\n" +
                "  \"model\": " + "\"" + modelName + "\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},\n" +
                "    {\"role\": \"user\", \"content\": \"" + prompt + "\"}\n" +
                "  ],\n" +
                "  \"max_tokens\": 1000,\n" +
                "  \"stream\": true\n" +
                "}";

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), jsonBody);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        Response response = httpClient.newCall(request).execute();

        if (!response.isSuccessful()) {
            responseObserver.onError(new RuntimeException("Error: " + response.body().string()));
            return;
        }

        // Since OpenAI uses SSE (Server-Sent Events) for streaming responses, you need to parse the stream accordingly
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
    }

    // This method will stream tokens from the Gemini model in Google Vertex AI
    private void streamGemini(String prompt, String modelName, StreamObserver<ChatResponsePart> responseObserver) throws IOException {
        // Define the project ID and location. Adjust as necessary.
        String projectId = System.getenv("GEMINI_PROJECT_ID");
        String location = "us-central1"; // Update as needed

        // Initialize the Vertex AI client
        try (VertexAI vertexAI = new VertexAI(projectId, location)) {
            // Create the generative model based on the model name
            GenerativeModel model = new GenerativeModel(modelName, vertexAI);

            // Stream the tokens from the model response
            try {
                model.generateContentStream(prompt)
                        .stream()
                        .forEach(response -> {
                            // Extract the token from the streamed response
                            List<com.google.cloud.vertexai.api.Candidate> candidatesList = response.getCandidatesList();

                            for (Candidate candidate: candidatesList){

                                List<Part> partsList = candidate.getContent().getPartsList();
                                for (Part part: partsList) {
                                    if (part.hasText()) {
                                        String token = part.getText();
                                        // Build and send a ChatResponsePart to the response observer
                                        ChatResponsePart responsePart = ChatResponsePart.newBuilder().setToken(token).build();
                                        responseObserver.onNext(responsePart);
                                    }
                                }
                            }
                        });

                // Notify that the stream is complete
                responseObserver.onCompleted();
                System.out.println("Streaming complete.");

            } catch (StatusRuntimeException e) {
                // Handle the error and notify the response observer
                responseObserver.onError(e);
                System.err.println("Error during streaming: " + e.getStatus());
            }
        }
    }

    private void streamAnthropic(String prompt, String modelName, StreamObserver<ChatResponsePart> responseObserver) throws IOException {
        // Implement streaming for Gemini here similar to streamOpenAI
    }

}

