package us.daconta.xlmeco;

import org.junit.jupiter.api.Test;
import us.daconta.xlmeco.provider.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedImageValidatorTest {
    byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out));
        return out.toByteArray();
    }
    @Test void acceptsDecodedPngAndJpegOnly() throws Exception {
        for (String format : List.of("png", "jpeg")) {
            byte[] bytes = image(format);
            assertArrayEquals(bytes, GeneratedImageValidator.decode("image/" + format, Base64.getEncoder().encodeToString(bytes)));
        }
    }
    @Test void rejectsBoundsMismatchAndCorruptionBeforePublishing() throws Exception {
        byte[] png = image("png");
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[GeneratedImageValidator.MAX_BYTES + 1],
                "not an image".getBytes(), Arrays.copyOf(png, 24)})
            assertThrows(StructuredImageProvider.Failure.class, () -> GeneratedImageValidator.validate("image/png", invalid));
        assertThrows(StructuredImageProvider.Failure.class, () -> GeneratedImageValidator.validate("image/jpeg", png));
        assertThrows(StructuredImageProvider.Failure.class, () -> GeneratedImageValidator.validate("image/webp", png));
        for (String value : Arrays.asList(null, "%%%", "A".repeat(4 * 1024 * 1024 + 1)))
            assertThrows(StructuredImageProvider.Failure.class, () -> GeneratedImageValidator.decode("image/png", value));
        for (int[] dimensions : new int[][]{{5000, 2}, {2, 5000}, {4096, 4096}, {0, 2}, {2, 0}}) {
            byte[] oversized = png.clone();
            ByteBuffer.wrap(oversized, 16, 8).putInt(dimensions[0]).putInt(dimensions[1]);
            assertThrows(StructuredImageProvider.Failure.class, () -> GeneratedImageValidator.validate("image/png", oversized));
        }
    }
}
