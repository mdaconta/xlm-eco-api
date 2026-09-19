package us.daconta.xlmeco;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.net.ServerSocket;
import java.lang.management.ManagementFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual entrypoint in a child process with synthetic external state. */
class ServerStartupBoundaryTest {
    @TempDir Path temp;
    int launch(String config, String secrets) throws Exception {
        var args = new ArrayList<String>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        args.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // Instrument the child with the same agent; shutdown appends coverage to the aggregate.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(args::add);
        args.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "us.daconta.xlmeco.GrpcXlmServer"));
        var builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().remove("XLM_CONFIG_DIR"); builder.environment().remove("XLM_SECRETS_DIR");
        if (config != null) builder.environment().put("XLM_CONFIG_DIR", config);
        if (secrets != null) builder.environment().put("XLM_SECRETS_DIR", secrets);
        var log = temp.resolve("startup-" + UUID.randomUUID() + ".log"); builder.redirectOutput(log.toFile());
        var process = builder.start();
        boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Startup boundary test did not terminate");
        String output = Files.readString(log);
        assertTrue(output.contains("XLM startup failed"), output);
        assertFalse(output.contains("synthetic-admin"));
        return process.exitValue();
    }
    @Test void missingRelativeAndCheckoutDirectoriesAreRejected() throws Exception {
        assertEquals(1, launch(null, null));
        assertEquals(1, launch("relative", temp.toString()));
        assertEquals(1, launch(Path.of(".").toAbsolutePath().toString(), temp.toString()));
    }
    @Test void failedSecondListenerCleansUpAndPersistsValidCatalog() throws Exception {
        Path config = Files.createDirectory(temp.resolve("config")), secrets = Files.createDirectory(temp.resolve("secrets"));
        Files.writeString(secrets.resolve("admin.token"), "synthetic-admin-token-01234567890123456789");
        try (var occupied = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Files.writeString(config.resolve("server.properties"), "server.port=0\nadmin.port=" + occupied.getLocalPort() + "\n");
            assertEquals(1, launch(config.toString(), secrets.toString()));
            assertTrue(Files.exists(config.resolve("registry.json")));
            // A second startup reaches the same collision rather than a leaked process lock.
            assertEquals(1, launch(config.toString(), secrets.toString()));
        }
    }
}
