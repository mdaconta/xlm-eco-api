package us.daconta.xlmeco.provider.impl;

import com.google.api.gax.rpc.HeaderProvider;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.cloud.aiplatform.v1.ModelName;
import com.google.cloud.aiplatform.v1.EndpointName;
import com.google.cloud.aiplatform.v1.PredictRequest;
import com.google.cloud.aiplatform.v1.PredictResponse;
import com.google.cloud.aiplatform.v1.PredictionServiceClient;
import com.google.cloud.aiplatform.v1.PredictionServiceSettings;
import com.google.protobuf.Value;
import io.grpc.StatusRuntimeException;
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
import java.util.Map;
import java.util.Properties;

public class GoogleProvider extends AbstractGenerativeProvider implements ChatProvider, EmbeddingProvider {
    public static final String VERSION = "1.0";
    public static final String PROVIDER_NAME = "google";
    public static final String PROJECT_ID = "project_id";
    public static final String LOCATION = "location";
    public static final String PROPERTY_LOCATION = LOCATION;
    public static final String PROPERTY_PROJECT_ID = PROJECT_ID;
    public static final String PROPERTY_API_KEY = GenerativeProvider.API_KEY;
    public static final String PROPERTY_URL_CHAT = GenerativeProvider.PROPERTY_URL_CHAT;
    public static final String PROPERTY_URL_EMBEDDING = GenerativeProvider.PROPERTY_URL_EMBEDDING;
    public static final String PROPERTY_DEFAULT_MODEL_LM = GenerativeProvider.PROPERTY_DEFAULT_MODEL_LM;
    public static final String PROPERTY_DEFAULT_MODEL_EMBEDDING = GenerativeProvider.PROPERTY_DEFAULT_MODEL_EMBEDDING;
    private static final String BASE_URL_PATTERN = "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict";

    // Configuration Properties read from property file
    private Properties configProperties;
    private String apiKey;
    private String projectId;
    private String location;
    private String chatURL;
    private String embeddingURL;
    private String defaultEmbeddingModel;
    private String defaultLmModel;

    public GoogleProvider() { }

    public void initialize(Properties configProperties) {
        this.configProperties = configProperties;
        apiKey = configProperties.getProperty(PROPERTY_API_KEY);
        projectId = configProperties.getProperty(PROPERTY_PROJECT_ID);
        location = configProperties.getProperty(PROPERTY_LOCATION);
        chatURL = configProperties.getProperty(PROPERTY_URL_CHAT);
        embeddingURL = configProperties.getProperty(PROPERTY_URL_EMBEDDING);
        defaultEmbeddingModel = configProperties.getProperty(PROPERTY_DEFAULT_MODEL_EMBEDDING);
        defaultLmModel = configProperties.getProperty(PROPERTY_DEFAULT_MODEL_LM);
    }

    private String generateChatUrl() {
        //return String.format(BASE_URL_PATTERN, location, projectId, location, defaultLmModel); // Use chat model ID
        return "https://generativelanguage.googleapis.com/v1beta/models/" + defaultLmModel + ":generateContent?key=" + apiKey;
    }

    private String generateEmbeddingUrl() {
        return String.format(BASE_URL_PATTERN, location, projectId, location, defaultEmbeddingModel); // Use embedding model ID
    }


    @Override
    public String generateChatResponse(ChatRequest request) throws Exception {
        String modelName = request.getModelName();
        String prompt = request.getPrompt();

        if (modelName == null || modelName.isEmpty())
            modelName = defaultLmModel;

        String output = null;
        if (apiKey != null && !apiKey.isEmpty()) {
            output =  generateChatResponseRest(request);
        } else {
            output = textInput(projectId, location, modelName, prompt);
        }

        return output;
    }

    private String generateChatResponseRest(ChatRequest request) throws IOException {
        String restChatUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + request.getModelName() + ":generateContent?key=" + apiKey;
        OkHttpClient client = new OkHttpClient();

        // Adjust JSON structure to match API requirements
        JSONObject contentObject = new JSONObject()
                .put("parts", new JSONArray()
                        .put(new JSONObject().put("text", request.getPrompt())));

        JSONObject jsonBody = new JSONObject()
                .put("contents", new JSONArray().put(contentObject));

        RequestBody body = RequestBody.create(
                jsonBody.toString(), MediaType.parse("application/json"));

        Request httpRequest = new Request.Builder()
                .url(restChatUrl)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            JSONObject jsonResponse = new JSONObject(response.body().string());
            // Adjust as per actual JSON structure returned by the API
            return jsonResponse.optString("output", ""); // Replace "output" with actual key if different
        }
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

        if (apiKey != null && !apiKey.isEmpty()) {
            streamChatResponseRest(request, responseObserver);
        } else {
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

                                for (Candidate candidate : candidatesList) {

                                    List<Part> partsList = candidate.getContent().getPartsList();
                                    for (Part part : partsList) {
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
    }

    private void streamChatResponseRest(ChatRequest request, StreamObserver<ChatResponsePart> responseObserver) throws IOException {
        String restChatUrl = generateChatUrl();
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonBody = new JSONObject()
                .put("model", request.getModelName())
                .put("prompt", request.getPrompt());

        RequestBody body = RequestBody.create(
                jsonBody.toString(), MediaType.parse("application/json"));

        Request httpRequest = new Request.Builder()
                .url(restChatUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            JSONObject jsonResponse = new JSONObject(response.body().string());

            // Assuming the response has tokens as an array
            JSONArray tokens = jsonResponse.getJSONArray("tokens");
            for (int i = 0; i < tokens.length(); i++) {
                String token = tokens.getString(i);
                ChatResponsePart responsePart = ChatResponsePart.newBuilder().setToken(token).build();
                responseObserver.onNext(responsePart);
            }

            responseObserver.onCompleted();
        }
    }

    @Override
    public List<Float> generateEmbedding(String text, ModelParameters params) {
        try {
            if (apiKey != null && !apiKey.isEmpty()) {
                return generateEmbeddingRest(text);
            } else {
                String endpoint = "us-central1-aiplatform.googleapis.com:443";
                PredictionServiceSettings settings =
                        PredictionServiceSettings.newBuilder().setEndpoint(endpoint).build();

                PredictionServiceClient predictionServiceClient = PredictionServiceClient.create(settings);
                // Construct the endpoint name
                EndpointName endpointName = EndpointName.ofProjectLocationPublisherModelName(projectId, location, "google", defaultEmbeddingModel);

                // Prepare the instance
                Value instance = Value.newBuilder().setStringValue(text).build();
                List<Value> instances = new ArrayList<>();
                instances.add(instance);

                // Build the PredictRequest
                PredictRequest request = PredictRequest.newBuilder()
                        .setEndpoint(endpoint.toString())
                        .addAllInstances(instances)
                        .build();

                // Call the prediction service

                var response = predictionServiceClient.predict(request);
                var predictions = response.getPredictionsList();

                // Process the predictions to extract embeddings
                // (Assuming the response contains embeddings in a specific format)
                List<Float> embedding = new ArrayList<>();
                for (Value prediction : predictions) {
                    // Extract the embedding values from the prediction
                    // Adjust this according to the actual response structure
                    var embeddingValues = prediction.getStructValue().getFieldsMap().get("embeddings").getListValue();
                    for (Value val : embeddingValues.getValuesList()) {
                        embedding.add((float) val.getNumberValue());
                    }
                }

                return embedding;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error during prediction: " + e.getMessage(), e);
        }
    }

    private List<Float> generateEmbeddingRest(String text) {
        String restEmbeddingUrl = generateEmbeddingUrl();
        List<Float> embedding = new ArrayList<>();
        OkHttpClient client = new OkHttpClient();

        JSONObject jsonBody = new JSONObject().put("input", text);
        RequestBody body = RequestBody.create(
                jsonBody.toString(), MediaType.parse("application/json"));

        Request httpRequest = new Request.Builder()
                .url(restEmbeddingUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            JSONObject jsonResponse = new JSONObject(response.body().string());
            JSONArray embeddingArray = jsonResponse.getJSONArray("embedding");

            for (int i = 0; i < embeddingArray.length(); i++) {
                embedding.add((float) embeddingArray.getDouble(i));
            }
        } catch (IOException e) {
            throw new RuntimeException("Error during embedding generation with REST API", e);
        }
        return embedding;
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