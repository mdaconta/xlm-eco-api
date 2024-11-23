package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

public class GrpcXlmServer {
    public static final String version = "0.03";
    private static final Logger logger = Logger.getLogger(GrpcXlmServer.class.getName());

    private static Properties loadProperties(String fileName) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = GrpcXlmServer.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input == null) {
                throw new FileNotFoundException("Sorry, unable to find " + fileName);
            }
            properties.load(input);
        }
        return properties;
    }

    public static void main(String[] args) throws Exception {
        // Load properties from config file
        Properties properties = loadProperties("config.properties");

        // Read the port from properties
        int port = Integer.parseInt(properties.getProperty("server.port"));

        // Build and start the gRPC server
        Server server = ServerBuilder
                .forPort(port)  // Choose the port you want the server to run on (e.g., 50051)
                .addService(new XlmEcosystemServiceImpl(properties))  // Register your service implementation
                .build();

        logger.info("XLM Server started V" + version + ", listening on port " + port);
        server.start();

        // Ensure the server is kept running
        server.awaitTermination();
    }
}

