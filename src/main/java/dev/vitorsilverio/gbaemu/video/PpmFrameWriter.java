package dev.vitorsilverio.gbaemu.video;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// Escritor simples de framebuffer ARGB para PPM binario.
public final class PpmFrameWriter {
    private PpmFrameWriter() {
    }

    public static void write(Path path, int[] argb, int width, int height) throws IOException {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("framebuffer size does not match dimensions");
        }

        try (OutputStream output = Files.newOutputStream(path)) {
            output.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (int color : argb) {
                output.write((color >>> 16) & 0xFF);
                output.write((color >>> 8) & 0xFF);
                output.write(color & 0xFF);
            }
        }
    }
}
