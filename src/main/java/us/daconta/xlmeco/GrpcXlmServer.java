package us.daconta.xlmeco;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import us.daconta.xlmeco.admin.*;
import us.daconta.xlmeco.security.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Explicit listeners and external state. No credentials are loaded from packaged resources. */
public final class GrpcXlmServer {
    public static final String version="0.05";
    public static void main(String[] args) {
        try { run(); }
        catch(Exception e) { System.err.println("XLM startup failed: check external configuration, credentials and listener TLS settings"); System.exit(1); }
    }
    private static void run() throws Exception {
        Path config=externalDirectory("XLM_CONFIG_DIR"), secretPath=externalDirectory("XLM_SECRETS_DIR");
        Properties settings=new Properties();
        Path settingsFile=config.resolve("server.properties");
        if(Files.exists(settingsFile))try(var in=Files.newInputStream(settingsFile)){settings.load(in);}
        // Only catalog/bootstrap non-secret values are read; credentials have one separate authority.
        validateStartupSettings(settings);
        ExternalSecrets secrets=new ExternalSecrets(secretPath);
        AdminAuthInterceptor auth=new AdminAuthInterceptor(secrets.requireAdminToken());
        var inference=ListenerSecurity.builder(settings.getProperty("server.bind","127.0.0.1"),Integer.parseInt(settings.getProperty("server.port","50052")),
                optionalPath(settings,"server.tls_certificate"),optionalPath(settings,"server.tls_private_key"));
        var admin=ListenerSecurity.builder(settings.getProperty("admin.bind","127.0.0.1"),Integer.parseInt(settings.getProperty("admin.port","50053")),
                optionalPath(settings,"admin.tls_certificate"),optionalPath(settings,"admin.tls_private_key"));
        try(ProviderRegistry registry=new ProviderRegistry(config,secrets,settings)) {
            inference.addService(new XlmEcosystemServiceImpl(registry));
            settings.putIfAbsent("feature.vectordb.enabled","false");
            inference.addService(new VectorDbServiceImpl(settings));
            Server publicServer=inference.build(), adminServer=admin.addService(ServerInterceptors.intercept(new AdminService(registry),auth)).build();
            Thread shutdown=new Thread(()-> { publicServer.shutdownNow();adminServer.shutdownNow(); });
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                publicServer.start(); adminServer.start();
                System.out.println("XLM ready; inference port="+publicServer.getPort()+", admin port="+adminServer.getPort());
                publicServer.awaitTermination();
            } finally {
                publicServer.shutdownNow();adminServer.shutdownNow();
                publicServer.awaitTermination(10,TimeUnit.SECONDS);adminServer.awaitTermination(10,TimeUnit.SECONDS);
                try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch(IllegalStateException ignored) {}
            }
        }
    }
    static void validateStartupSettings(Properties settings) {
        for(String key:settings.stringPropertyNames()) {
            if(key.endsWith("api_key")||key.equals("admin.token")||key.equals("admin_token"))
                throw new IllegalArgumentException("Credentials require external secret files");
        }
    }
    private static Path optionalPath(Properties properties,String key) {
        String value=properties.getProperty(key,"");return value.isBlank()?null:Path.of(value);
    }
    private static Path externalDirectory(String name) throws Exception {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalArgumentException("External directory is required");
        Path path=Path.of(value);
        if(!path.isAbsolute())throw new IllegalArgumentException("External directory must be absolute");
        Path actual=path.toRealPath();
        for(Path ancestor=actual;ancestor!=null;ancestor=ancestor.getParent()) {
            if(Files.exists(ancestor.resolve(".git")))throw new IllegalArgumentException("Mutable state and secrets must be outside source control");
        }
        return actual;
    }
}
