package us.daconta.xlmeco;

import us.daconta.xlmeco.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;

public class VectorDbTestClient {

    public static void main(String[] args) {
        // Validate and parse command-line arguments
        if (args.length < 2) {
            System.out.println("Usage: java VectorDbTestClient <host> <port>");
            System.exit(1);
        }

        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + args[1]);
            System.exit(1);
            return; // This return is redundant but ensures no further execution
        }

        // Connect to the gRPC server
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        VectorDbServiceGrpc.VectorDbServiceBlockingStub vectorDbStub = VectorDbServiceGrpc.newBlockingStub(channel);

        try {
            // 1. Define the schema
            System.out.println("Defining vector schema...");
            DefineVectorSchemaRequest schemaRequest = DefineVectorSchemaRequest.newBuilder()
                    .setEmbeddingDimension(8)
                    .addFields(MetadataField.newBuilder()
                            .setName("category")
                            .setType(FieldType.STRING)
                            .build())
                    .build();

            DefineVectorSchemaResponse schemaResponse = vectorDbStub.defineVectorSchema(schemaRequest);
            System.out.println("Schema defined successfully: " + schemaResponse.getSuccess());

            // 2. Upsert a vector
            System.out.println("Upserting a vector...");
            UpsertVectorRequest upsertRequest = UpsertVectorRequest.newBuilder()
                    .setId("vector-1")
                    .addAllEmbedding(List.of(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f))
                    .setContent("This is a test string for vector-1.")
                    .putMetadata("category", MetadataValue.newBuilder().setStringValue("test1").build())
                    .build();

            UpsertVectorResponse upsertResponse = vectorDbStub.upsertVector(upsertRequest);
            System.out.println("Vector upserted successfully with ID: " + upsertResponse.getId());

            // 2a. Upsert another vector
            System.out.println("Upserting another vector...");
            UpsertVectorRequest upsertRequest2 = UpsertVectorRequest.newBuilder()
                    .setId("vector-2")
                    .addAllEmbedding(List.of(0.1f, 0.22f, 0.3f, 0.41f, 0.5f, 0.6f, 0.7f, 0.8f))
                    .setContent("This is a new test string for vector-2.")
                    .putMetadata("category", MetadataValue.newBuilder().setStringValue("test2").build())
                    .build();

            UpsertVectorResponse upsertResponse2 = vectorDbStub.upsertVector(upsertRequest2);
            System.out.println("Vector upserted successfully with ID: " + upsertResponse2.getId());

            // 3. Retrieve the vector by ID
            System.out.println("Retrieving vector...");
            GetVectorRequest getRequest = GetVectorRequest.newBuilder()
                    .setId("vector-1")
                    .build();

            GetVectorResponse getResponse = vectorDbStub.getVector(getRequest);
            System.out.println("Retrieved vector: ID=" + getResponse.getId() +
                    ", Embedding=" + getResponse.getEmbeddingList() +
                    ", Content=" + getResponse.getContent());

            // 4. Perform a similarity search
            System.out.println("Performing similarity search...");
            SearchVectorsRequest searchRequest = SearchVectorsRequest.newBuilder()
                    .addAllQueryEmbedding(List.of(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f))
                    .setTopK(3)
                    .build();

            SearchVectorsResponse searchResponse = vectorDbStub.searchVectors(searchRequest);
            searchResponse.getResultsList().forEach(result ->
                    System.out.println("Search result: ID=" + result.getId() + ", Score=" + result.getScore())
            );

            // 5. Delete the vector
            System.out.println("Deleting vector...");
            DeleteVectorRequest deleteRequest = DeleteVectorRequest.newBuilder()
                    .setId("vector-1")
                    .build();

            DeleteVectorResponse deleteResponse = vectorDbStub.deleteVector(deleteRequest);
            System.out.println("Vector deleted successfully: " + deleteResponse.getSuccess());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Shutdown the channel
            channel.shutdown();
        }
    }
}


