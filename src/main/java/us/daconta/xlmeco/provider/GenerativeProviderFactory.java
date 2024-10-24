package us.daconta.xlmeco.provider;

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
}
