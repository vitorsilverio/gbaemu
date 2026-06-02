package dev.vitorsilverio.gbaemu.desktop;

import java.awt.image.BufferedImage;

/// Conversor de framebuffer ARGB para BufferedImage.
public final class GbaFrameImage {
    private GbaFrameImage() {
    }

    public static BufferedImage fromArgb(int[] argb, int width, int height) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("framebuffer size does not match dimensions");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
