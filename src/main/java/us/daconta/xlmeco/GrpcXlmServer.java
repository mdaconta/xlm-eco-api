package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

public class GrpcXlmServer {
    public static final String version = "0.04";
    private static final Logger logger = Logger.getLogger(GrpcXlmServer.class.getName());

    private static Properties loadProperties(String fileName) throws IOException {
        logger.info(() -> "Loading properties from " + fileName);

        // Load defaults from the template so that new providers (like Ollama) are available
        // even if an older config.properties file is missing their entries.
        Properties defaultProperties = new Properties();
        try (InputStream defaultsStream = GrpcXlmServer.class.getClassLoader()
                .getResourceAsStream(fileName + ".template")) {
            if (defaultsStream != null) {
                defaultProperties.load(defaultsStream);
                logger.info(() -> "Loaded default properties from " + fileName + ".template");
            } else {
                logger.warning(() -> "No template file found for " + fileName + "; proceeding without defaults");
            }
        }

        Properties properties = new Properties(defaultProperties);
        try (InputStream input = GrpcXlmServer.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input != null) {
                properties.load(input);
            } else {
                logger.warning(() -> "Config file " + fileName + " not found; using defaults only");
            }
        }
        logger.info(() -> "Loaded properties: " + properties);
        return properties;
    }

    public static void main(String[] args) throws Exception {
        // Load properties from config file
        Properties properties = loadProperties("config.properties");

        // Read the port from properties
        int port = Integer.parseInt(properties.getProperty("server.port"));
        logger.info(() -> "Configured server port: " + port);

        // Build and start the gRPC server
        Server server = ServerBuilder
                .forPort(port)  // Choose the port you want the server to run on (e.g., 50051)
                .addService(new XlmEcosystemServiceImpl(properties))  // Register your service implementation
                .addService(new VectorDbServiceImpl(properties))
                .build();

        logger.info("XLM Server started V" + version + ", listening on port " + port);
        server.start();

        // Ensure the server is kept running
        logger.info("gRPC server started; awaiting termination");
        server.awaitTermination();
    }
}

