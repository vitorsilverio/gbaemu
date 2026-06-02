package dev.vitorsilverio.gbaemu.desktop;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GbaFrameImageTest {
    @Test
    void convertsArgbFramebufferToBufferedImage() {
        BufferedImage image = GbaFrameImage.fromArgb(new int[]{0xFFFF0000, 0xFF00FF00}, 2, 1);

        assertEquals(2, image.getWidth());
        assertEquals(1, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0xFF00FF00, image.getRGB(1, 0));
    }

    @Test
    void rejectsFramebufferWithUnexpectedSize() {
        assertThrows(IllegalArgumentException.class, () -> GbaFrameImage.fromArgb(new int[1], 2, 1));
    }
}
