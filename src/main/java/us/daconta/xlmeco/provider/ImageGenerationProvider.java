package us.daconta.xlmeco.provider;

/** Text-to-image capability. Independent of structured image input. */
public interface ImageGenerationProvider {
    String CAPABILITY = "image_generation";
    Result generateImage(Input input) throws StructuredImageProvider.Failure;
    record Input(String prompt, String model) {}
    record Result(String mimeType, byte[] data, String model) {}
}
