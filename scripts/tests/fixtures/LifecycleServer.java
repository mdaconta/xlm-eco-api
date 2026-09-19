import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** No listeners or credentials: a real JVM that retains its own test JAR. */
public final class LifecycleServer {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(LifecycleServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile retained = new JarFile(jar.toFile())) {
            retained.size();
            Path ready = Path.of(System.getenv("XLM_CONFIG_DIR"), "fixture-ready.txt");
            Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
            while (true) Thread.sleep(1000);
        }
    }
}
