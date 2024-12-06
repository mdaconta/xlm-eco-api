package us.daconta.xlmeco;

import io.grpc.stub.StreamObserver;
import org.json.JSONObject;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.provider.*;
import us.daconta.xlmeco.provider.FieldType;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of the VectorDbService defined in the proto file.
 */
public class VectorDbServiceImpl extends VectorDbServiceGrpc.VectorDbServiceImplBase {
    private static final String FEATURE_VECTORDB_ENABLED="feature.vectordb.enabled";
    private Map<String, VectorDbProvider> providers = new ConcurrentHashMap<String, VectorDbProvider>();
    // TEMP: determine how you let the client choose the provider
    private VectorDbProvider vectorDbProvider;

    public VectorDbServiceImpl(Properties properties) {
        // check if the VectorDB feature is enabled
        String strVectordbEnabled = (String) properties.get(FEATURE_VECTORDB_ENABLED);
        boolean vectordbEnabled = strVectordbEnabled.equalsIgnoreCase("true") ? true: false;

        if (vectordbEnabled) {
            System.out.println("Vector DBs enabled...");
            this.providers = VectorDbProviderFactory.loadProviders(properties);
            vectorDbProvider = this.providers.values().iterator().next();  // TEMP, get the first one
        } else {
            System.out.println("Vector DBs disabled.");
        }
    }

    @Override
    public void defineVectorSchema(DefineVectorSchemaRequest request, StreamObserver<DefineVectorSchemaResponse> responseObserver) {
        Map<String, FieldType> schema = new HashMap<>();
        for (MetadataField field : request.getFieldsList()) {
            schema.put(field.getName(), FieldType.valueOf(field.getType().name()));
        }

        int embeddingDimension = request.getEmbeddingDimension();
        boolean success = vectorDbProvider.defineVectorSchema(embeddingDimension, schema);
        DefineVectorSchemaResponse response = DefineVectorSchemaResponse.newBuilder().setSuccess(success).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void upsertVector(UpsertVectorRequest request, StreamObserver<UpsertVectorResponse> responseObserver) {
        boolean success = vectorDbProvider.upsertVector(
                request.getId(),
                request.getEmbeddingList(),
                request.getContent(),
                request.getMetadataMap()
        );
        UpsertVectorResponse response = UpsertVectorResponse.newBuilder().setId(request.getId()).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getVector(GetVectorRequest request, StreamObserver<GetVectorResponse> responseObserver) {
        VectorRecord record = vectorDbProvider.getVector(request.getId());
        if (record != null) {
            GetVectorResponse.Builder builder = GetVectorResponse.newBuilder()
                    .setId(record.getId())
                    .addAllEmbedding(record.getEmbedding())
                    .setContent(record.getContent());

            // determine if metadata field
            if (record.getMetadata() != null) {
                JSONObject metadataObject = record.getMetadata();

                // Iterate over the JSON object keys and convert them to metadata map entries
                for (String key : metadataObject.keySet()) {
                    Object value = metadataObject.get(key);

                    // Create a MetadataValue.Builder
                    MetadataValue.Builder metadataValueBuilder = MetadataValue.newBuilder();

                    // Determine the type of the value and set the corresponding field
                    if (value instanceof String) {
                        metadataValueBuilder.setStringValue((String) value);
                    } else if (value instanceof Integer) {
                        metadataValueBuilder.setIntValue((Integer) value);
                    } else if (value instanceof Float || value instanceof Double) {
                        metadataValueBuilder.setFloatValue(((Number) value).floatValue());
                    } else {
                        throw new IllegalArgumentException("Unsupported metadata value type: " + value.getClass().getName());
                    }

                    // Add the metadata key-value pair to the response
                    builder.putMetadata(key, metadataValueBuilder.build());
                }
            }
            responseObserver.onNext(builder.build());
        } else {
            // If vector not found, respond accordingly (optional)
            responseObserver.onNext(GetVectorResponse.newBuilder().build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void deleteVector(DeleteVectorRequest request, StreamObserver<DeleteVectorResponse> responseObserver) {
        boolean success = vectorDbProvider.deleteVector(request.getId());
        DeleteVectorResponse response = DeleteVectorResponse.newBuilder().setSuccess(success).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void searchVectors(SearchVectorsRequest request, StreamObserver<SearchVectorsResponse> responseObserver) {
        java.util.List<VectorSearchResult> results = vectorDbProvider.searchVectors(
                request.getQueryEmbeddingList(),
                request.getTopK()
        );
        SearchVectorsResponse.Builder builder = SearchVectorsResponse.newBuilder();
        for (VectorSearchResult result : results) {
            builder.addResults(SearchResult.newBuilder()
                    .setId(result.getId())
                    .setScore(result.getScore())
                    .build());
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
