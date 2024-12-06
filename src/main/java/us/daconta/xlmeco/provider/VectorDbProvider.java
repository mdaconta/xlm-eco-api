package us.daconta.xlmeco.provider;

import us.daconta.xlmeco.grpc.MetadataValue;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A provider interface for vector database operations.
 */
public interface VectorDbProvider {
    public static final String PROPERTY_HOST = "host";
    public static final String PROPERTY_PORT = "port";

    // Return the provider name (e.g., "milvus", "pinecone", "pgvector")
    String getProviderName();

    public void initialize(Properties props);

    boolean defineVectorSchema(int embeddingDimension, Map<String, FieldType> optionalMetadata);

    boolean upsertVector(String id, List<Float> embedding, String content, Map<String, MetadataValue> optionalMetadata);

    VectorRecord getVector(String id);

    boolean deleteVector(String id);

    List<VectorSearchResult> searchVectors(List<Float> queryEmbedding, int topK);
}

