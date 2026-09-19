package us.daconta.xlmeco.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SecretValidationTest {
    @TempDir Path directory;
    @Test void rejectsInvalidAuthoritiesAndUnsafeCredentialFiles() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new ExternalSecrets(null));
        assertThrows(IllegalArgumentException.class,()->new ExternalSecrets(Path.of("relative")));
        var secrets=new ExternalSecrets(directory);
        assertThrows(IllegalArgumentException.class,()->secrets.providerCredential(null));
        assertThrows(IllegalStateException.class,secrets::requireAdminToken);
        for(String value:new String[]{"", "your-key", "replace-this", "placeholder", "changeme", "${SECRET}", "<secret>"}) {
            Files.writeString(directory.resolve("google.key"),value); assertFalse(secrets.credentialPresent("google"));
        }
        for(String value:new String[]{"a b", "unicode-\u0100"}) {
            Files.writeString(directory.resolve("google.key"),value);
            var failure=assertThrows(IllegalStateException.class,()->secrets.credentialPresent("google")); assertNull(failure.getCause()); assertFalse(failure.toString().contains(value));
        }
        Files.createDirectory(directory.resolve("invalid.key"));
        assertThrows(IllegalStateException.class,()->secrets.credentialPresent("invalid"));
        for(String token:new String[]{null,"short","x".repeat(4097),"a".repeat(31)+" ","a".repeat(31)+"\u0100","placeholder"+"a".repeat(32)})
            assertThrows(IllegalArgumentException.class,()->ExternalSecrets.validateAdminToken(token));
        assertDoesNotThrow(()->ExternalSecrets.validateAdminToken("synthetic-credential-value-1234567890"));
    }
    @Test void rejectsAmbiguousListenerAddressPortAndIncompleteTlsWithoutLeakingPaths() {
        for(String address:new String[]{null,"fe80::1%eth0"}) assertThrows(IllegalArgumentException.class,()->ListenerSecurity.builder(address,0,null,null));
        for(int port:new int[]{-1,65536}) assertThrows(IllegalArgumentException.class,()->ListenerSecurity.builder("127.0.0.1",port,null,null));
        assertThrows(IllegalArgumentException.class,()->ListenerSecurity.builder("127.0.0.1",0,null,directory.resolve("key")));
        var failure=assertThrows(IllegalArgumentException.class,()->ListenerSecurity.builder("127.0.0.1",0,directory.resolve("missing-cert"),directory.resolve("missing-key")));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains(directory.toString()));
    }
}
