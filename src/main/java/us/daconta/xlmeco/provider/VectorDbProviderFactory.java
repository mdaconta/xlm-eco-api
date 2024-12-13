package us.daconta.xlmeco.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Utility class to load VectorDBProvider implementations using Java SPI.
 */
public class VectorDbProviderFactory {

    private static final ServiceLoader<VectorDbProvider> loader = ServiceLoader.load(VectorDbProvider.class);

    /**
     * Retrieve the first available VectorDBProvider implementation.
     *
     * @return an instance of VectorDBProvider
     * @throws RuntimeException if no implementations are found
     */
    public static VectorDbProvider getVectorDBProvider() {
        for (VectorDbProvider provider : loader) {
            // Additional logic for selecting a specific provider can be added here
            return provider;
        }
        throw new RuntimeException("No VectorDBProvider implementations found!");
    }

    public static Map<String, VectorDbProvider> loadProviders(Properties properties) {
        Map<String, VectorDbProvider> providers = new HashMap<>();

        for (VectorDbProvider provider : loader) {
            String providerName = provider.getProviderName().toLowerCase();
            Properties providerProps = GenerativeProviderFactory.filterPropertiesForPrefix(properties, providerName + ".");

            if (!providerProps.isEmpty()) {
                provider.initialize(providerProps);  // Initialize with filtered properties
                providers.put(providerName, provider);
            }
        }
        return providers;
    }
}
