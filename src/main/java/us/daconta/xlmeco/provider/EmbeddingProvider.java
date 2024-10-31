package us.daconta.xlmeco.provider;

import us.daconta.xlmeco.grpc.ModelParameters;

import java.util.List;

public interface EmbeddingProvider extends GenerativeProvider {

    // Generate embeddings for a given text
    List<Float> generateEmbedding(String text, ModelParameters parameters);
}
