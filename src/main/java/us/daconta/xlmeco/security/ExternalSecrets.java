package us.daconta.xlmeco.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Optional;

/** Read-only Human-managed secrets. Values never appear in diagnostics or object stringification. */
public final class ExternalSecrets {
    private static final int MAX_BYTES = 4096;
    private final Path directory;

    public ExternalSecrets(Path directory) {
        if (directory == null || !directory.isAbsolute())
            throw new IllegalArgumentException("External secret directory must be absolute");
        this.directory = directory.normalize();
    }

    public Optional<String> providerCredential(String providerId) {
        if (providerId == null || !providerId.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid provider identifier");
        return read(providerId + ".key");
    }

    public boolean credentialPresent(String providerId) { return providerCredential(providerId).isPresent(); }

    public String requireAdminToken() {
        String token = read("admin.token").orElseThrow(() -> new IllegalStateException("Administrative credential required"));
        validateAdminToken(token);
        return token;
    }

    static void validateAdminToken(String token) {
        if (token == null || token.length() < 32 || token.length() > MAX_BYTES
                || !token.chars().allMatch(c -> c >= 33 && c <= 126) || placeholder(token))
            throw new IllegalArgumentException("Administrative credential must be a strong external token");
    }

    private Optional<String> read(String filename) {
        Path file = directory.resolve(filename);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
                throw new IOException();
            byte[] bytes;
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException();
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            java.util.Arrays.fill(bytes, (byte) 0);
            if (value.isEmpty() || placeholder(value)) return Optional.empty();
            if (!value.chars().allMatch(c -> c >= 33 && c <= 126)) throw new IOException();
            return Optional.of(value);
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("External credential could not be loaded");
        }
    }

    private static boolean placeholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("your_") || normalized.contains("your-") || normalized.contains("replace")
                || normalized.contains("placeholder") || normalized.contains("changeme")
                || normalized.startsWith("${") || normalized.startsWith("<");
    }
}
