package us.daconta.xlmeco.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import us.daconta.xlmeco.grpc.StructuredImageErrorCode;

/** Checks dimensions before decoding, bounding both compressed bytes and raster allocation. */
public final class GeneratedImageValidator {
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    private GeneratedImageValidator() {}

    public static byte[] decode(String mime, String encoded) throws StructuredImageProvider.Failure {
        if (encoded == null || encoded.length() > 4 * ((MAX_BYTES + 2) / 3)) throw malformed();
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            validate(mime, bytes);
            return bytes;
        } catch (IllegalArgumentException e) { throw malformed(); }
    }

    public static void validate(String mime, byte[] bytes) throws StructuredImageProvider.Failure {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES
                || !("image/png".equals(mime) || "image/jpeg".equals(mime))) throw malformed();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw malformed();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!("image/png".equals(mime) ? "png" : "jpeg").equalsIgnoreCase(format)) throw malformed();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096
                        || (long) width * height > 16_000_000) throw malformed();
                // ImageIO can recover truncated JPEGs with warnings; fail closed on warnings.
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                if (reader.read(0) == null || warning[0]) throw malformed();
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException e) { throw malformed(); }
    }

    public static StructuredImageProvider.Failure malformed() {
        return new StructuredImageProvider.Failure(StructuredImageErrorCode.MALFORMED_PROVIDER_RESPONSE,
                "Provider returned an invalid or oversized image", false, 0);
    }
}
