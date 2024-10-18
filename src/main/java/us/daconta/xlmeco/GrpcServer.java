package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class GrpcServer {

    public static void main(String[] args) throws Exception {
        // Build and start the gRPC server
        Server server = ServerBuilder
                .forPort(50051)  // Choose the port you want the server to run on (e.g., 50051)
                .addService(new XlmEcosystemServiceImpl())  // Register your service implementation
                .build();

        System.out.println("Server started, listening on port 50051");
        server.start();

        // Ensure the server is kept running
        server.awaitTermination();
    }
}

