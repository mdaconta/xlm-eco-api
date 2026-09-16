package us.daconta.xlmeco.provider;

import us.daconta.xlmeco.grpc.StructuredImageErrorCode;

/** The one-image structured generation capability, independent of provider wire formats. */
public interface StructuredImageProvider {
    boolean supportsStructuredImageModel(String model);

    Result generateStructuredImage(Input input) throws Failure;

    record Input(String instructions, String mimeType, byte[] image, String model, String jsonSchema) {}

    record Result(String jsonPayload, String model) {}

    final class Failure extends Exception {
        private final StructuredImageErrorCode code;
        private final boolean retryable;
        private final int httpStatus;

        public Failure(StructuredImageErrorCode code, String message, boolean retryable, int httpStatus) {
            super(message);
            this.code = code;
            this.retryable = retryable;
            this.httpStatus = httpStatus;
        }

        public StructuredImageErrorCode code() { return code; }
        public boolean retryable() { return retryable; }
        public int httpStatus() { return httpStatus; }
    }
}
