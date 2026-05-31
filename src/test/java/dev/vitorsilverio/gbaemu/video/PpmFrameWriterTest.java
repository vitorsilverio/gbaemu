package dev.vitorsilverio.gbaemu.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PpmFrameWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesBinaryPpmFromArgbFramebuffer() throws Exception {
        Path frame = tempDir.resolve("frame.ppm");

        PpmFrameWriter.write(frame, new int[]{0xFFFF0000, 0xFF00FF00}, 2, 1);

        assertArrayEquals(new byte[]{
                'P', '6', '\n', '2', ' ', '1', '\n', '2', '5', '5', '\n',
                (byte) 0xFF, 0, 0,
                0, (byte) 0xFF, 0
        }, Files.readAllBytes(frame));
    }
}
