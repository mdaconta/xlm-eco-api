package us.daconta.xlmeco.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public interface GenerativeProvider {
    public static final String API_KEY = "api_key";
    public static final String PROPERTY_URL_CHAT = "chat_url";
    public static final String PROPERTY_URL_EMBEDDING = "embedding_url";
    public static final String PROPERTY_DEFAULT_MODEL_LM = "default_lm_model";
    public static final String PROPERTY_DEFAULT_MODEL_EMBEDDING = "default_embedding_model";

    // Capability Constants
    public static final String CAPABILITY_CHAT = "chat";
    public static final String CAPABILITY_EMBEDDING = "embedding";
    public static final String CAPABILITY_AGENTS = "agents";
    public static final String CAPABILITY_RAG = "rag";

    // Enum defining service levels, similar to JDBC compliance levels
    enum ServiceLevel {
        LEVEL_1, LEVEL_2, LEVEL_3
    }

    public void initialize(Properties props);

    // Return the provider name (e.g., "openai", "anthropic")
    String getProviderName();

    // Return the level of service provided by the provider
    ServiceLevel getServiceLevel();

    // TBD: Each "feature" offering MAY have its own VERSION that needs to be tracked and reported.

    // Return a map of supported services (e.g., {"chat": true, "embedding": false})
    default Map<String, Boolean> getSupportedCapabilities() {
        Map<String, Boolean> capabilities = new HashMap<>();
        capabilities.put(CAPABILITY_CHAT, supportsChat());
        capabilities.put(CAPABILITY_EMBEDDING, supportsEmbeddings());
        capabilities.put(CAPABILITY_RAG, supportsRAG());
        capabilities.put(CAPABILITY_AGENTS, supportsAgents());
        return capabilities;
    }

    // Does the provider support chat functionality?
    boolean supportsChat();

    // Does the provider support embeddings?
    boolean supportsEmbeddings();

    // Does the provider support RAG?
    boolean supportsRAG();

    // You can add more advanced capabilities here (assistants, agents, etc.)
    boolean supportsAgents();
}
