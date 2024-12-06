package us.daconta.xlmeco.provider.impl;

import com.azure.core.implementation.jackson.ObjectMapperShim;
import com.google.protobuf.ProtocolStringList;
import io.milvus.client.MilvusServiceClient;
import io.milvus.exception.ParamException;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.response.FieldDataWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.response.ShowCollResponseWrapper;
import org.eclipse.jetty.util.ajax.JSON;
import org.json.JSONObject;
import us.daconta.xlmeco.*;
import us.daconta.xlmeco.grpc.MetadataValue;
import us.daconta.xlmeco.provider.VectorDbProvider;
import us.daconta.xlmeco.provider.VectorRecord;
import us.daconta.xlmeco.provider.VectorSearchResult;

import java.util.*;

/**
 * Milvus implementation of VectorDBProvider.
 */
public class MilvusDbProvider implements VectorDbProvider {
    private static final String COLLECTION_NAME = "vectors";
    private static final String PRIMARY_KEY_FIELD = "id";
    private static final String EMBEDDING_FIELD = "embedding";
    private static final String CONTENT_FIELD = "content";
    private static final String METADATA_FIELD = "metadata";
    private int embeddingDimension = 768;
    private String host="";
    int port = 0;

    private MilvusServiceClient milvusClient;

    public MilvusDbProvider() {
    }

    private void createCollectionIfNotExists() {
        // Check if the collection already exists
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build()
        );

        if (!hasCollection.getData()) {
            // Define the schema for the collection
            List<FieldType> fields = List.of(
                    FieldType.newBuilder()
                            .withName(PRIMARY_KEY_FIELD)
                            .withDescription("Primary Key")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(255)
                            .withPrimaryKey(true)
                            .withAutoID(false)
                            .build(),
                    FieldType.newBuilder()
                            .withName(EMBEDDING_FIELD)
                            .withDescription("Embedding Vector")
                            .withDataType(DataType.FloatVector)
                            .withDimension(embeddingDimension)
                            .build(),
                    FieldType.newBuilder()
                            .withName(CONTENT_FIELD)
                            .withDescription("Content Field")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(1024)
                            .build()
            );

            // Create the collection
            R<RpcStatus> response = milvusClient.createCollection(
                    CreateCollectionParam.newBuilder()
                            .withCollectionName(COLLECTION_NAME)
                            .withDescription("Vector collection for embeddings")
                            .withShardsNum(2)
                            .addFieldType(fields.get(0))
                            .addFieldType(fields.get(1))
                            .addFieldType(fields.get(2))
                            .build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create collection: " + response.getMessage());
            } else {
                System.out.println("Collection '" + COLLECTION_NAME + "' created successfully.");
            }
        } else {
            System.out.println("Collection '" + COLLECTION_NAME + "' already exists.");
        }
    }

    private void createIndexIfNotExists() {
        // Describe the index to check if it exists
        R<DescribeIndexResponse> describeIndexResponse = milvusClient.describeIndex(
                DescribeIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build()
        );

        if (describeIndexResponse.getStatus() == R.Status.Success.getCode()) {
            // Index exists
            if (describeIndexResponse.getData() != null && !describeIndexResponse.getData().getIndexDescriptionsList().isEmpty()) {
                System.out.println("Index already exists for collection '" + COLLECTION_NAME + "'.");
                return;
            } else {
                System.out.println("Index descriptions list is empty. Proceeding to create index.");
            }
        } else if (describeIndexResponse.getStatus() == R.Status.IndexNotExist.getCode() ||
                describeIndexResponse.getMessage().contains("index not found")) {
            // Index does not exist, proceed to create it
            System.out.println("Index does not exist for collection '" + COLLECTION_NAME + "'. Creating index...");
        } else {
            // Some other error occurred
            throw new RuntimeException("Failed to describe index: " + describeIndexResponse.getMessage());
        }

        // Create an index on the embedding field
        R<RpcStatus> response = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withFieldName(EMBEDDING_FIELD)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.L2)
                        .withExtraParam("{\"nlist\":128}")
                        .withSyncMode(Boolean.TRUE) // Wait for index creation
                        .build()
        );

        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to create index: " + response.getMessage());
        } else {
            System.out.println("Index created successfully for collection '" + COLLECTION_NAME + "'.");
        }

        // Explicitly load the collection after creating the index
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build()
        );

        if (loadResponse.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to load collection after creating index: " + loadResponse.getMessage());
        }

        System.out.println("Collection '" + COLLECTION_NAME + "' loaded successfully after index creation.");
    }

    private boolean collectionExists(String collectionName) {
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (hasCollection.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to check collection existence: " + hasCollection.getMessage());
        }
        return hasCollection.getData();
    }

    private boolean indexExists(String collectionName) {
        R<DescribeIndexResponse> describeIndexResponse = milvusClient.describeIndex(
                DescribeIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (describeIndexResponse.getStatus() == R.Status.IndexNotExist.getCode()) {
            return false;
        } else if (describeIndexResponse.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to describe index: " + describeIndexResponse.getMessage());
        }
        return !describeIndexResponse.getData().getIndexDescriptionsList().isEmpty();
    }

    @Override
    public String getProviderName() {
        return "milvus";
    }

    @Override
    public void initialize(Properties props) {
        host = props.getProperty(VectorDbProvider.PROPERTY_HOST);
        port = Integer.parseInt(props.getProperty(VectorDbProvider.PROPERTY_PORT));
        // Connect to the Milvus server
        this.milvusClient = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host) // Update to your server's host
                        .withPort(port)       // Update to your server's port
                        .build()
        );
    }

    @Override
    public boolean defineVectorSchema( int embeddingDimension, Map<String, us.daconta.xlmeco.provider.FieldType> optionalMetadata) {
        this.embeddingDimension = embeddingDimension;

        // Check if the collection already exists
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build()
        );

        if (hasCollection.getData()) {
            // Optional: Drop the existing collection if schema needs to be updated
            System.out.println("Collection '" + COLLECTION_NAME + "' already exists.");
            // You may choose to drop and recreate the collection if schemas don't match
        } else {
            // Define the mandatory fields
            List<FieldType> fields = new ArrayList<>();
            fields.add(
                    FieldType.newBuilder()
                            .withName(PRIMARY_KEY_FIELD)
                            .withDescription("Primary Key")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(255)
                            .withPrimaryKey(true)
                            .withAutoID(false)
                            .build()
            );
            fields.add(
                    FieldType.newBuilder()
                            .withName(EMBEDDING_FIELD)
                            .withDescription("Embedding Vector")
                            .withDataType(DataType.FloatVector)
                            .withDimension(embeddingDimension)
                            .build()
            );
            fields.add(
                    FieldType.newBuilder()
                            .withName(CONTENT_FIELD)
                            .withDescription("Content Field")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(1024)
                            .build()
            );

            // Add optional metadata fields
            if (optionalMetadata != null) {
                fields.add(
                        FieldType.newBuilder()
                                .withName(METADATA_FIELD)
                                .withDescription("Metadata Field")
                                .withDataType(DataType.VarChar)
                                .withMaxLength(8192) // Adjust the max length as needed
                                .build()
                );
            }

            // Create the collection with the defined fields
            R<RpcStatus> response = milvusClient.createCollection(
                    CreateCollectionParam.newBuilder()
                            .withCollectionName(COLLECTION_NAME)
                            .withDescription("Vector collection with dynamic schema")
                            .withShardsNum(2)
                            .withFieldTypes(fields)
                            .build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create collection: " + response.getMessage());
            } else {
                System.out.println("Collection '" + COLLECTION_NAME + "' created successfully.");
            }
        }

        // Create index after collection creation
        createIndexIfNotExists();

        return true;
    }


    @Override
    public boolean upsertVector(String id, List<Float> embedding, String content, Map<String, MetadataValue> metadata) {
        List<InsertParam.Field> fields = new ArrayList<>();
        // Insert or update a vector record in Milvus
        List<InsertParam.Field> mandatoryFields = List.of(
                new InsertParam.Field(PRIMARY_KEY_FIELD, Collections.singletonList(id)),
                new InsertParam.Field(EMBEDDING_FIELD, Collections.singletonList(embedding)),
                new InsertParam.Field(CONTENT_FIELD, Collections.singletonList(content))
        );

        fields.addAll(mandatoryFields);

        if (metadata != null) {
            // Serialize metadata to JSON
            String metadataJson = "";
            if (metadata != null && !metadata.isEmpty()) {
                metadataJson = serializeMetadataToJson(metadata);
            }
            fields.add(new InsertParam.Field(METADATA_FIELD, Collections.singletonList(metadataJson)));

        }

        R<MutationResult> result = milvusClient.insert(
                InsertParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withFields(fields)
                        .build()
        );

        if (result.getStatus() != R.Status.Success.getCode()) {
            System.err.println("Failed to upsert vector: " + result.getMessage());
            return false;
        }

        // Flush the collection to ensure data is persisted and available for querying
        R<FlushResponse> flushResponse = milvusClient.flush(
                FlushParam.newBuilder()
                        .addCollectionName(COLLECTION_NAME)
                        .build()
        );

        if (flushResponse.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to flush collection: " + flushResponse.getMessage());
        }

        return true;
    }

    private String serializeMetadataToJson(Map<String, MetadataValue> metadata) {
        try {
            // Convert MetadataValue objects to their actual values
            Map<String, Object> metadataMap = new HashMap<>();
            for (Map.Entry<String, MetadataValue> entry : metadata.entrySet()) {
                metadataMap.put(entry.getKey(), extractValue(entry.getValue()));
            }

            return new JSONObject(metadataMap).toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize metadata to JSON", e);
        }
    }

    private Object extractValue(MetadataValue metadataValue) {
        switch (metadataValue.getValueCase()) {
            case STRING_VALUE:
                return metadataValue.getStringValue();
            case INT_VALUE:
                return metadataValue.getIntValue();
            case FLOAT_VALUE:
                return metadataValue.getFloatValue();
            default:
                return null;
        }
    }

    public VectorRecord getVector(String id) {
        // Ensure the collection is loaded before querying
        loadCollectionIfNotLoaded(COLLECTION_NAME);

        // Query Milvus to retrieve the vector by its ID
        R<QueryResults> result = milvusClient.query(
                QueryParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withExpr(PRIMARY_KEY_FIELD + " == \"" + id + "\"") // Filter by primary key
                        .addOutField(EMBEDDING_FIELD)
                        .addOutField(CONTENT_FIELD)
                        //.addOutField(METADATA_FIELD)   // TBD - how to determine if metadata exists?  Examine API
                        .build()
        );

        // Check if the query execution was successful and the result is not null
        if (result.getStatus() != R.Status.Success.getCode() || result.getData() == null) {
            throw new RuntimeException("Query failed: " + result.getMessage());
        }

        QueryResultsWrapper wrapper = new QueryResultsWrapper(result.getData());

        // Extract the mandatory fields
        FieldDataWrapper embeddingField = wrapper.getFieldWrapper(EMBEDDING_FIELD);
        FieldDataWrapper contentField = wrapper.getFieldWrapper(CONTENT_FIELD);

        // Check if the mandatory fields contain data
        if (embeddingField == null || contentField == null || embeddingField.getRowCount() == 0) {
            return null; // Return null if data is missing
        }

        // Extract embedding (assume one row since we query by unique ID)
        List<List<Float>> embeddingData = (List<List<Float>>) embeddingField.getFieldData();
        List<Float> embedding = embeddingData.get(0);

        // Extract content
        List<String> contentData = (List<String>) contentField.getFieldData();
        String content = contentData.get(0);

        // Extract additional metadata fields (JSON)
        FieldDataWrapper metadataField = null;
        String jsonMetadata = null;
        try {
            wrapper.getFieldWrapper(METADATA_FIELD);
            List<String> metadataData = (List<String>) metadataField.getFieldData();
            jsonMetadata = metadataData.get(0);
        } catch (ParamException paramException) {
            // do nothing
        }
        // Return a new VectorRecord with the retrieved data
        return new VectorRecord(id, embedding, content, jsonMetadata);
    }

    @Override
    public boolean deleteVector(String id) {
        // Delete the vector by its ID
        R<MutationResult> result = milvusClient.delete(
                DeleteParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withExpr(PRIMARY_KEY_FIELD + " == \"" + id + "\"")
                        .build()
        );

        return result.getStatus() == R.Status.Success.getCode();
    }

    private void loadCollectionIfNotLoaded(String collectionName) {
        // Get the load state of the collection
        R<GetLoadStateResponse> loadStateResponse = milvusClient.getLoadState(
                GetLoadStateParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );

        if (loadStateResponse.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("Failed to get load state: " + loadStateResponse.getMessage());
        }

        LoadState loadState = loadStateResponse.getData().getState();

        if (loadState != LoadState.LoadStateLoaded) {
            System.out.println("Loading collection '" + collectionName + "' into memory...");
            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );

            if (loadResponse.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to load collection: " + loadResponse.getMessage());
            }

            // Wait until the collection is fully loaded
            while (true) {
                try {
                    Thread.sleep(500); // Wait for 500 milliseconds before checking again
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted while waiting for collection to load.", e);
                }

                loadStateResponse = milvusClient.getLoadState(
                        GetLoadStateParam.newBuilder()
                                .withCollectionName(collectionName)
                                .build()
                );

                if (loadStateResponse.getStatus() != R.Status.Success.getCode()) {
                    throw new RuntimeException("Failed to get load state: " + loadStateResponse.getMessage());
                }

                loadState = loadStateResponse.getData().getState();

                if (loadState == LoadState.LoadStateLoaded) {
                    System.out.println("Collection '" + collectionName + "' loaded successfully.");
                    break;
                } else if (loadState == LoadState.LoadStateNotLoad) {
                    throw new RuntimeException("Failed to load collection: Load failed.");
                } else {
                    System.out.println("Waiting for collection '" + collectionName + "' to load. Current state: " + loadState);
                }
            }
        } else {
            System.out.println("Collection '" + collectionName + "' is already loaded.");
        }
    }



    @Override
    public List<VectorSearchResult> searchVectors(List<Float> queryEmbedding, int topK) {
        // Ensure the collection is loaded before querying
        loadCollectionIfNotLoaded(COLLECTION_NAME);

        // Search for similar vectors using the query embedding
        R<SearchResults> result = milvusClient.search(
                SearchParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withMetricType(MetricType.L2)
                        .withVectorFieldName(EMBEDDING_FIELD)
                        .withTopK(topK)
                        .withVectors(Collections.singletonList(queryEmbedding))
                        .withParams("{\"nprobe\":10}")
                        .addOutField(PRIMARY_KEY_FIELD)
                        .addOutField(EMBEDDING_FIELD)
                        .addOutField(CONTENT_FIELD)
                        .build()
        );

        if (result.getStatus() != R.Status.Success.getCode()) {
            return Collections.emptyList();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<VectorSearchResult> searchResults = new ArrayList<>();

        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        System.out.println("The result of No.0 target vector:");
        for (SearchResultsWrapper.IDScore score:scores) {
            String resultId = (String) score.get(PRIMARY_KEY_FIELD);

            List<Float> embedding = (List<Float>)score.get(EMBEDDING_FIELD);
            System.out.println(embedding);

            float floatScore = score.getScore();

            String content = (String)score.get(CONTENT_FIELD);
            System.out.println(content);

            searchResults.add(new VectorSearchResult(resultId, floatScore, embedding, content, null));
        }

        // Iterate through the available rows in the search results
//        for (int i = 0; i < wrapper.getFieldWrapper(PRIMARY_KEY_FIELD).getRowCount(); i++) {
//            // Retrieve the ID for the current result
//            FieldDataWrapper idField = wrapper.getFieldWrapper(PRIMARY_KEY_FIELD);
//            // Extract content
//            List<String> idData = (List<String>) idField.getFieldData();
//            String resultId = idData.get(0);
//
//            // Retrieve the similarity score for the current result
//
//            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(i).get(0);
//            float score = idScore.getScore();
//
//            // Extract the mandatory fields
//            FieldDataWrapper embeddingField = wrapper.getFieldWrapper(EMBEDDING_FIELD);
//            FieldDataWrapper contentField = wrapper.getFieldWrapper(CONTENT_FIELD);
//
//            // Extract embedding (assume one row since we query by unique ID)
//            List<List<Float>> embeddingData = (List<List<Float>>) embeddingField.getFieldData();
//            List<Float> embedding = embeddingData.get(0);
//
//            // Extract content
//            List<String> contentData = (List<String>) contentField.getFieldData();
//            String content = contentData.get(0);
//
//            // Add the result to the search results list
//            searchResults.add(new VectorSearchResult(resultId, score, embedding, content, null));
//        }

        return searchResults;
    }
}
