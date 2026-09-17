package us.daconta.xlmeco.provider.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.json.JSONObject;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;
import us.daconta.xlmeco.provider.StructuredImageProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

/** Bounded transport with no upstream error text, credentials or exception causes escaping. */
final class ProviderHttp {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final OkHttpClient client;
    ProviderHttp(Properties properties) {
        int seconds = Integer.parseInt(properties.getProperty("timeout_seconds", "150"));
        client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(Duration.ofSeconds(seconds))
                .readTimeout(Duration.ofSeconds(seconds)).build();
    }
    JSONObject post(String url, String header, String credential, JSONObject payload, boolean anthropic)
            throws StructuredImageProvider.Failure {
        try {
            Request.Builder builder = new Request.Builder().url(url)
                    .header(header, credential == null ? "" : credential)
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")));
            if (anthropic) builder.header("anthropic-version", "2023-06-01");
            try (Response response = client.newCall(builder.build()).execute()) {
                int status = response.code();
                if (!response.isSuccessful()) throw httpFailure(status);
                if (response.body() == null) throw malformed();
                byte[] bytes = response.peekBody(1_048_577).bytes();
                if (bytes.length > 1_048_576) throw malformed();
                var parsed = JSON.readTree(bytes);
                if (parsed == null || !parsed.isObject()) throw malformed();
                return new JSONObject(JSON.writeValueAsString(parsed));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw malformed();
        } catch (java.io.InterruptedIOException e) {
            throw new StructuredImageProvider.Failure(StructuredImageErrorCode.PROVIDER_TIMEOUT, "Provider request timed out", true, 0);
        } catch (IOException e) {
            throw new StructuredImageProvider.Failure(StructuredImageErrorCode.PROVIDER_UNAVAILABLE, "Provider connection failed", true, 0);
        }
    }
    static StructuredImageProvider.Failure httpFailure(int status) {
        StructuredImageErrorCode code = switch (status) {
            case 401, 403 -> StructuredImageErrorCode.PROVIDER_AUTHENTICATION;
            case 429 -> StructuredImageErrorCode.PROVIDER_RATE_LIMIT;
            case 408, 504 -> StructuredImageErrorCode.PROVIDER_TIMEOUT;
            default -> status >= 500 ? StructuredImageErrorCode.PROVIDER_UNAVAILABLE : StructuredImageErrorCode.PROVIDER_FAILURE;
        };
        return new StructuredImageProvider.Failure(code, "Provider returned HTTP " + status,
                status == 429 || status == 408 || status >= 500, status);
    }
    static StructuredImageProvider.Failure malformed() {
        return new StructuredImageProvider.Failure(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                "Provider response is malformed or incomplete", false, 0);
    }
    static String structured(String content) throws StructuredImageProvider.Failure {
        try {
            var parsed = JSON.readTree(content);
            if (parsed == null || !parsed.isObject()) throw new IOException();
            return JSON.writeValueAsString(parsed);
        } catch (IOException e) {
            throw new StructuredImageProvider.Failure(StructuredImageErrorCode.INVALID_STRUCTURED_OUTPUT,
                    "Provider output is not a JSON object", false, 0);
        }
    }
    static us.daconta.xlmeco.provider.SafeProviderFailure safeTextFailure(String provider, StructuredImageProvider.Failure failure) {
        return new us.daconta.xlmeco.provider.SafeProviderFailure(provider, failure.code(), failure.httpStatus());
    }
}
