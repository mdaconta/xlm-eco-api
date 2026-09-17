package us.daconta.xlmeco.provider.impl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import java.util.Base64;
import java.util.Properties;

/** Anthropic Messages API adapter. Catalog membership is validated by the XLM registry. */
public class AnthropicProvider extends AbstractGenerativeProvider implements StructuredImageProvider, us.daconta.xlmeco.provider.ChatProvider, us.daconta.xlmeco.provider.EmbeddingProvider {
    public static final String PROVIDER_NAME = "anthropic";
    private String apiKey, chatURL;
    private int outputTokens;
    private ProviderHttp http;
    public void initialize(Properties properties) {
        apiKey = properties.getProperty(API_KEY);
        chatURL = properties.getProperty(PROPERTY_URL_CHAT, "https://api.anthropic.com/v1/messages");
        outputTokens = Integer.parseInt(properties.getProperty("output_tokens", "4096"));
        http = new ProviderHttp(properties);
    }
    public Result generateStructuredImage(Input input) throws Failure {
        try {
            JSONObject image = new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", input.mimeType())
                    .put("data", Base64.getEncoder().encodeToString(input.image())));
            JSONObject payload = new JSONObject().put("model", input.model()).put("max_tokens", outputTokens)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content",
                            new JSONArray().put(image).put(new JSONObject().put("type", "text").put("text", input.instructions())))))
                    .put("output_config", new JSONObject().put("format", new JSONObject().put("type", "json_schema")
                            .put("schema", new JSONObject(input.jsonSchema()))));
            JSONObject envelope = http.post(chatURL, "x-api-key", apiKey, payload, true);
            if (!"end_turn".equals(envelope.optString("stop_reason"))) throw ProviderHttp.malformed();
            JSONArray content = envelope.getJSONArray("content");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) text.append(block.getString("text"));
            }
            return new Result(ProviderHttp.structured(text.toString()), envelope.optString("model", input.model()));
        } catch (JSONException | IllegalArgumentException e) { throw ProviderHttp.malformed(); }
    }
    public String generateChatResponse(us.daconta.xlmeco.grpc.ChatRequest request) {
        throw new UnsupportedOperationException("anthropic: UNSUPPORTED_CAPABILITY");
    }
    public void streamChatResponse(us.daconta.xlmeco.grpc.ChatRequest request,
            io.grpc.stub.StreamObserver<us.daconta.xlmeco.grpc.ChatResponsePart> observer) {
        throw new UnsupportedOperationException("anthropic: UNSUPPORTED_CAPABILITY");
    }
    public java.util.List<Float> generateEmbedding(String text, us.daconta.xlmeco.grpc.ModelParameters parameters) {
        throw new UnsupportedOperationException("anthropic: UNSUPPORTED_CAPABILITY");
    }
    public boolean supportsStructuredImageModel(String model) { return model != null && !model.isBlank(); }
    public String getProviderName() { return PROVIDER_NAME; }
    public boolean supportsChat() { return false; }
    public boolean supportsEmbeddings() { return false; }
    public boolean supportsRAG() { return false; }
    public boolean supportsAgents() { return false; }
}
