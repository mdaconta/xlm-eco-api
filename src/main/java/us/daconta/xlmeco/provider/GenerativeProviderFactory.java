package us.daconta.xlmeco.provider;

import us.daconta.xlmeco.GrpcXlmServer;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;

public class GenerativeProviderFactory {

    private static final Logger logger = Logger.getLogger(GenerativeProviderFactory.class.getName());


    public static GenerativeProvider getProvider(String providerName) {
        for (GenerativeProvider provider : ServiceLoader.load(GenerativeProvider.class)) {
            if (provider.getProviderName().equalsIgnoreCase(providerName)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Provider not supported: " + providerName);
    }

    public static Map<String, GenerativeProvider> loadProviders(Properties properties) {
        Map<String, GenerativeProvider> providers = new HashMap<>();

        for (GenerativeProvider provider : ServiceLoader.load(GenerativeProvider.class)) {
            String providerName = provider.getProviderName().toLowerCase();
            logger.info("Loading provider: " + providerName);
            Properties providerProps = filterPropertiesForPrefix(properties, providerName + ".");

            if (!providerProps.isEmpty()) {
                provider.initialize(providerProps);  // Initialize with filtered properties
                providers.put(providerName, provider);
            } else {
                logger.info("Provider properties are empty for: " + providerName);
            }
        }
        return providers;
    }

    public static Properties filterPropertiesForPrefix(Properties properties, String prefix) {
        Properties filteredProps = new Properties();

        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) filteredProps.setProperty(name.substring(prefix.length()), properties.getProperty(name));
        }
        return filteredProps;
    }
}
