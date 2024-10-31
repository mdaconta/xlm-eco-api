package us.daconta.xlmeco.provider.impl;

import us.daconta.xlmeco.provider.GenerativeProvider;

import java.util.Properties;

public abstract class AbstractGenerativeProvider implements GenerativeProvider {
    public static String version = "";

    public abstract void initialize (Properties props);

    @Override
    public String getProviderName() {
        return "UNKNOWN";
    }

    @Override
    public ServiceLevel getServiceLevel() {
        return ServiceLevel.LEVEL_1;
    }

    @Override
    public boolean supportsChat() {
        return false;
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public boolean supportsRAG() {
        return false;
    }

    @Override
    public boolean supportsAgents() {
        return false;
    }
}
