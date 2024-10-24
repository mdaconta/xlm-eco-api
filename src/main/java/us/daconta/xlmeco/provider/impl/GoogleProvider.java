package us.daconta.xlmeco.provider.impl;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import io.grpc.StatusRuntimeException;
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
import java.util.List;

public class GoogleProvider implements ChatProvider {

    @Override
    public String generateChatResponse(ChatRequest request) throws Exception {
        String modelName = request.getModelName();
        String prompt = request.getPrompt();
        String projectId = System.getenv("GEMINI_PROJECT_ID");

        // TBD: Fix this location as part of the provider config?
        String location = "us-central1";

        if (modelName == null || modelName.isEmpty())
            modelName = "gemini-1.5-flash-001"; // default

        String output = textInput(projectId, location, modelName, prompt);

        return output;
    }

    public String getProviderName() {
        return "google";
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
    public void streamChatResponse(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws Exception {
        String modelName = request.getModelName();
        String prompt = request.getPrompt();

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
                            List<Candidate> candidatesList = response.getCandidatesList();

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

    @Override
    public ServiceLevel getServiceLevel() {
        return null;
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
}