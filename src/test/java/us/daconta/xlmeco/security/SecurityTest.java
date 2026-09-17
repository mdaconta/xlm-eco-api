package us.daconta.xlmeco.security;

import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.grpc.netty.shaded.io.netty.channel.embedded.EmbeddedChannel;
import io.grpc.netty.shaded.io.netty.buffer.UnpooledByteBufAllocator;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslHandler;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {
    private static final String TOKEN = "synthetic-admin-auth-marker-1234567890";
    private static final MethodDescriptor<Empty, Empty> METHOD = MethodDescriptor.<Empty, Empty>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("test.Admin/Status")
            .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance())).build();
    @TempDir Path directory;

    private ServerServiceDefinition service() {
        return ServerInterceptors.intercept(ServerServiceDefinition.builder("test.Admin")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(Empty.getDefaultInstance()); observer.onCompleted();
                })).build(), new AdminAuthInterceptor(TOKEN));
    }

    private Empty invoke(Channel channel, String token) {
        if (token != null) {
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), token);
            channel = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
        }
        return ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT.withDeadlineAfter(4, TimeUnit.SECONDS), Empty.getDefaultInstance());
    }

    @Test void loopbackStillRequiresAuthentication() throws Exception {
        Server server = ListenerSecurity.builder("127.0.0.1", 0, null, null).addService(service()).build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            for (String input : new String[]{null, "Bearer wrong", TOKEN, "bearer " + TOKEN}) {
                var error = assertThrows(StatusRuntimeException.class, () -> invoke(channel, input));
                assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
                assertFalse(error.toString().contains(TOKEN));
            }
            assertEquals(Empty.getDefaultInstance(), invoke(channel, "Bearer " + TOKEN));
        } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(); }
    }

    @Test void insecureRemoteAndAmbiguousAddressesRejected() {
        for (String address : new String[]{"0.0.0.0", "::", "192.0.2.1", "localhost", "127.1", "2130706433", ""})
            assertThrows(IllegalArgumentException.class, () -> ListenerSecurity.builder(address, 0, null, null));
        assertNotNull(ListenerSecurity.builder("::1", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> ListenerSecurity.builder("127.0.0.1", 0, directory.resolve("cert"), null));
        assertThrows(IllegalArgumentException.class, () -> new AdminAuthInterceptor("short"));
    }

    @Test void externalSecretsPresenceReloadAndSafeErrors() throws Exception {
        ExternalSecrets secrets = new ExternalSecrets(directory);
        assertFalse(secrets.credentialPresent("google"));
        Files.writeString(directory.resolve("google.key"), "YOUR_API_KEY");
        assertFalse(secrets.credentialPresent("google"));
        Files.writeString(directory.resolve("google.key"), "synthetic-provider-key");
        assertEquals("synthetic-provider-key", secrets.providerCredential("google").orElseThrow());
        Files.writeString(directory.resolve("google.key"), "synthetic-updated-key");
        assertEquals("synthetic-updated-key", secrets.providerCredential("google").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> secrets.providerCredential("../google"));
        Files.writeString(directory.resolve("admin.token"), TOKEN);
        assertEquals(TOKEN, secrets.requireAdminToken());
        Files.writeString(directory.resolve("google.key"), "sensitive-marker\ninvalid");
        var failure = assertThrows(IllegalStateException.class, () -> secrets.credentialPresent("google"));
        assertFalse(failure.toString().contains("sensitive-marker"));
        assertNull(failure.getCause());
        Files.writeString(directory.resolve("google.key"), "x".repeat(4097));
        assertThrows(IllegalStateException.class, () -> secrets.credentialPresent("google"));
    }

    @ParameterizedTest(name = "TLS mode {0}: 0 trusted, 1 untrusted, 2 wrong hostname, 3 plaintext")
    @ValueSource(ints = {0, 1, 2, 3})
    @EnabledIfEnvironmentVariable(named = "XLM_RUN_NETWORK_TLS_TESTS", matches = "true")
    void tlsTrustHostnameAndPlaintextEnforcedOverNetwork(int mode) throws Exception {
        Path[] material = certificate();
        Path cert = material[0], key = material[1];
        Server server = ListenerSecurity.builder("0.0.0.0", 0, cert, key).addService(service()).build().start();
        try {
            var builder = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort());
            if (mode == 0 || mode == 2) builder.sslContext(GrpcSslContexts.forClient().trustManager(cert.toFile()).build());
            if (mode == 1) builder.useTransportSecurity();
            if (mode == 2) builder.overrideAuthority("wrong.invalid");
            if (mode == 3) builder.usePlaintext();
            ManagedChannel channel = builder.build();
            try {
                if (mode == 0) assertEquals(Empty.getDefaultInstance(), invoke(channel, "Bearer " + TOKEN));
                else assertThrows(StatusRuntimeException.class, () -> invoke(channel, "Bearer " + TOKEN));
            } finally { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
        } finally { server.shutdownNow().awaitTermination(); }
    }

    @ParameterizedTest(name = "In-memory TLS: 0 trusted, 1 untrusted, 2 hostname mismatch: {0}")
    @ValueSource(ints = {0, 1, 2})
    void tlsEngineValidatesTrustAndHostnameWithoutNetworkInterception(int mode) throws Exception {
        Path[] material = certificate();
        var clientBuilder = GrpcSslContexts.forClient();
        if (mode != 1) clientBuilder.trustManager(material[0].toFile());
        SslHandler clientTls = clientBuilder.build().newHandler(UnpooledByteBufAllocator.DEFAULT,
                mode == 2 ? "wrong.invalid" : "localhost", 443);
        var parameters = clientTls.engine().getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        clientTls.engine().setSSLParameters(parameters);
        SslHandler serverTls = GrpcSslContexts.forServer(material[0].toFile(), material[1].toFile())
                .build().newHandler(UnpooledByteBufAllocator.DEFAULT);
        EmbeddedChannel client = new EmbeddedChannel(clientTls), server = new EmbeddedChannel(serverTls);
        try {
            for (int step = 0; step < 1000; step++) {
                client.runPendingTasks(); server.runPendingTasks();
                transfer(client, server); transfer(server, client);
                if (clientTls.handshakeFuture().isDone()
                        && (!clientTls.handshakeFuture().isSuccess() || serverTls.handshakeFuture().isDone())) break;
            }
            assertTrue(clientTls.handshakeFuture().isDone(), "TLS handshake must finish");
            assertEquals(mode == 0, clientTls.handshakeFuture().isSuccess());
            if (mode == 0) assertTrue(serverTls.handshakeFuture().isSuccess());
        } finally {
            try { client.finishAndReleaseAll(); } catch (Exception ignored) { /* expected failed handshake */ }
            try { server.finishAndReleaseAll(); } catch (Exception ignored) { /* expected failed handshake */ }
        }
    }

    private void transfer(EmbeddedChannel source, EmbeddedChannel target) {
        Object message;
        while ((message = source.readOutbound()) != null) {
            try { target.writeInbound(message); }
            catch (Exception ignored) { /* handshake future retains the failure for the assertion */ }
        }
    }

    private Path[] certificate() throws Exception {
        // Entirely synthetic short-lived certificate/key; no production credential is generated.
        Path store = directory.resolve("test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString();
        if (!Files.exists(Path.of(keytool))) keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "synthetic-test-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(store)) { keys.load(stream, "synthetic-test-password".toCharArray()); }
        Path cert = directory.resolve("cert.pem"), key = directory.resolve("key.pem");
        pem(cert, "CERTIFICATE", keys.getCertificate("test").getEncoded());
        pem(key, "PRIVATE KEY", keys.getKey("test", "synthetic-test-password".toCharArray()).getEncoded());
        return new Path[]{cert, key};
    }

    private void pem(Path path, String label, byte[] data) throws Exception {
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(data)
                + "\n-----END " + label + "-----\n");
    }
}
