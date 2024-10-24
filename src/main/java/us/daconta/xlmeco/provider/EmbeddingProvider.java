package us.daconta.xlmeco.provider;

public interface EmbeddingProvider extends GenerativeProvider {

    // Generate embeddings for a given text
    String generateEmbedding(String text) throws Exception;

}
