package us.daconta.xlmeco;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrpcXlmServerTest {
    @Test void startupAllowsNonSecretOutputTokenLimitsButRejectsCredentialEntries() {
        Properties settings=new Properties();
        settings.setProperty("google.output_tokens","4096");
        settings.setProperty("anthropic.output_tokens","2048");
        assertDoesNotThrow(()->GrpcXlmServer.validateStartupSettings(settings));
        for(String name:new String[]{"google.api_key","admin.token","admin_token"}) {
            Properties invalid=new Properties();invalid.putAll(settings);invalid.setProperty(name,"synthetic-marker");
            var failure=assertThrows(IllegalArgumentException.class,()->GrpcXlmServer.validateStartupSettings(invalid));
            assertFalse(failure.toString().contains("synthetic-marker"));
        }
    }
}
