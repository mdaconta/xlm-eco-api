package us.daconta.xlmeco.security;

import io.grpc.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Applies to the entire administration service, including discovery/read RPCs. */
public final class AdminAuthInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final byte[] expected;

    public AdminAuthInterceptor(String token) {
        ExternalSecrets.validateAdminToken(token);
        expected = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Iterable<String> values = headers.getAll(AUTHORIZATION);
        String supplied = null;
        int count = 0;
        if (values != null) for (String value : values) { supplied = value; count++; }
        if (count != 1 || supplied == null || supplied.length() > 4103
                || !supplied.chars().allMatch(c -> c >= 32 && c <= 126)
                || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.US_ASCII))) {
            call.close(Status.UNAUTHENTICATED.withDescription("Administrative authentication required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}
