package us.daconta.xlmeco.admin;

import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.grpc.*;
import us.daconta.xlmeco.security.AdminAuthInterceptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminValidationTest {
    @Test void invalidAdminRequestsAndDuplicateAuthorizationFailClosed() throws Exception {
        String token = "synthetic-admin-012345678901234567890123456789";
        try (var registry = ProviderRegistry.inMemory(new Properties())) {
            var server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new AdminService(registry), new AdminAuthInterceptor(token))).build().start();
            var channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            try {
                var header = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
                for (List<String> values : List.of(List.of("Bearer " + token, "Bearer " + token), List.of("x".repeat(4104)), List.of("Bearer bad"))) {
                    var metadata = new Metadata(); values.forEach(value -> metadata.put(header, value));
                    var denied = XlmAdminServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
                    assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class, () -> denied.getStatus(EmptyRequest.getDefaultInstance())).getStatus().getCode());
                }
                var metadata = new Metadata(); metadata.put(header, "Bearer " + token);
                var stub = XlmAdminServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
                assertEquals(5, stub.listProviders(AdminListProvidersRequest.getDefaultInstance()).getProvidersCount());
                assertTrue(stub.listModels(AdminListModelsRequest.getDefaultInstance()).getModelsCount() > 0);
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.getProvider(AdminProviderRequest.newBuilder().setProvider("missing").build())).getStatus().getCode());
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.listModels(AdminListModelsRequest.newBuilder().setProvider("missing").build())).getStatus().getCode());
                assertThrows(StatusRuntimeException.class, () -> stub.listProviders(AdminListProvidersRequest.newBuilder().setCapability("bad").build()));
                assertThrows(StatusRuntimeException.class, () -> stub.listModels(AdminListModelsRequest.newBuilder().setCapability("bad").build()));
                assertThrows(StatusRuntimeException.class, () -> stub.upsertModel(AdminUpsertModelRequest.newBuilder().setExpectedRevision(1)
                        .setModel(AdminModel.newBuilder().setProvider("openai").setModel("m").setEnabled(true).addCapabilities("chat").addCapabilities("chat")).build()));
                for (var selection : List.of(AdminDefault.newBuilder().setCapability("chat").setProvider("openai").build(),
                        AdminDefault.newBuilder().setCapability("chat").setModel("m").build()))
                    assertThrows(StatusRuntimeException.class, () -> stub.setDefault(AdminSetDefaultRequest.newBuilder().setExpectedRevision(1).setClear(true).setSelection(selection).build()));
                assertEquals(1, registry.snapshot().state().revision());
            } finally {
                channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                server.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
