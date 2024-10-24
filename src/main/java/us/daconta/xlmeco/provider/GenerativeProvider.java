package us.daconta.xlmeco.provider;

public interface GenerativeProvider {
    // Enum defining service levels, similar to JDBC compliance levels
    enum ServiceLevel {
        LEVEL_1, LEVEL_2
    }

    // Return the provider name (e.g., "openai", "anthropic")
    String getProviderName();

    // Return the level of service provided by the provider
    ServiceLevel getServiceLevel();

    // Does the provider support chat functionality?
    boolean supportsChat();

    // Does the provider support embeddings?
    boolean supportsEmbeddings();

    // Does the provider support RAG?
    boolean supportsRAG();

    // You can add more common capabilities here (assistants, agents, etc.)
}
