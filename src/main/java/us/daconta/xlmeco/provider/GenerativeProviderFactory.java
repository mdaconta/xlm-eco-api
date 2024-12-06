package us.daconta.xlmeco.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

public class GenerativeProviderFactory {

    private static ServiceLoader<GenerativeProvider> loader = ServiceLoader.load(GenerativeProvider.class);

    public static GenerativeProvider getProvider(String providerName) {
        for (GenerativeProvider provider : loader) {
            if (provider.getProviderName().equalsIgnoreCase(providerName)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Provider not supported: " + providerName);
    }

    public static Map<String, GenerativeProvider> loadProviders(Properties properties) {
        Map<String, GenerativeProvider> providers = new HashMap<>();

        for (GenerativeProvider provider : loader) {
            String providerName = provider.getProviderName().toLowerCase();
            Properties providerProps = filterPropertiesForPrefix(properties, providerName + ".");

            if (!providerProps.isEmpty()) {
                provider.initialize(providerProps);  // Initialize with filtered properties
                providers.put(providerName, provider);
            }
        }
        return providers;
    }

    public static Properties filterPropertiesForPrefix(Properties properties, String prefix) {
        Properties filteredProps = new Properties();

        properties.forEach((key, value) -> {
            if (key.toString().startsWith(prefix)) {
                filteredProps.put(key.toString().substring(prefix.length()), value);
            }
        });
        return filteredProps;
    }
}
