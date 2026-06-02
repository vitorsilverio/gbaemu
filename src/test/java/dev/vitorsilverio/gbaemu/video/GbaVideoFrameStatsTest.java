package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaVideoFrameStatsTest {
    @Test
    void capturesDisplayRegistersAndFrameDiversity() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0403);
        memory.write16(0x05000000, 0x0000);
        memory.write16(0x05000002, 0x03E0);
        memory.write16(0x06000000, 0x001F);

        int[] frame = video.renderFrame(memory);
        GbaVideoFrameStats stats = GbaVideoFrameStats.capture(memory, frame);

        assertEquals(0x0403, stats.dispcnt());
        assertEquals(3, stats.mode());
        assertFalse(stats.forcedBlank());
        assertEquals(0b0100, stats.enabledBackgroundMask());
        assertEquals(2, stats.uniqueColors());
        assertEquals(1, stats.nonBackdropPixels());
        assertEquals(1, stats.paletteNonZeroHalfwords());
        assertEquals(1, stats.vramNonZeroBytes());
    }

    @Test
    void reportsForcedBlankAndVisibleObjects() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x1080);
        memory.write16(0x07000000, 1 << 9);
        memory.write16(0x07000008, 0);

        int[] frame = video.renderFrame(memory);
        GbaVideoFrameStats stats = GbaVideoFrameStats.capture(memory, frame);

        assertTrue(stats.forcedBlank());
        assertTrue(stats.objectsEnabled());
        assertEquals(127, stats.visibleObjects());
    }
}
