package us.daconta.xlmeco.provider;

import java.util.Set;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;

/** Only finite, application-owned data may cross the text-provider failure boundary. */
public final class SafeProviderFailure extends RuntimeException {
    private static final Set<String> PROVIDERS = Set.of("openai", "google", "anthropic", "grok", "ollama");
    private final String provider;
    private final StructuredImageErrorCode code;
    private final int httpStatus;
    public SafeProviderFailure(String provider, StructuredImageErrorCode code, int httpStatus) {
        super(message(provider, code, httpStatus), null, false, false);
        this.provider = provider != null && PROVIDERS.contains(provider) ? provider : "provider";
        this.code = code == null ? StructuredImageErrorCode.PROVIDER_FAILURE : code;
        this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
    }
    private static String message(String provider, StructuredImageErrorCode code, int status) {
        return (provider != null && PROVIDERS.contains(provider) ? provider : "provider") + ": "
                + (code == null ? StructuredImageErrorCode.PROVIDER_FAILURE : code).name()
                + " HTTP " + (status >= 100 && status <= 599 ? status : 0);
    }
    public String safeMessage() { return getMessage(); }
    public String provider() { return provider; }
    public StructuredImageErrorCode code() { return code; }
    public int httpStatus() { return httpStatus; }
}
