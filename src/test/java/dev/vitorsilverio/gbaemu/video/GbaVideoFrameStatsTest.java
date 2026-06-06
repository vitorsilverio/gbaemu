package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.memory.GbaBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaVideoFrameStatsTest {

    private static GbaBus createBus() {
        GbaLcdTiming lcdTiming = new GbaLcdTiming(null);
        GbaBus bus = new GbaBus();
        bus.add(lcdTiming);
        bus.add(new GbaVideoMemory(() -> lcdTiming.readByte(0x04000000) & 0x7));
        return bus;
    }

    @Test
    void capturesDisplayRegistersAndFrameDiversity() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0403);
        bus.write16(0x05000000, 0x0000);
        bus.write16(0x05000002, 0x03E0);
        bus.write16(0x06000000, 0x001F);

        int[] frame = video.renderFrame(bus);
        GbaVideoFrameStats stats = GbaVideoFrameStats.capture(bus, frame);

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
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x1080);
        bus.write16(0x07000000, 1 << 9);
        bus.write16(0x07000008, 0);

        int[] frame = video.renderFrame(bus);
        GbaVideoFrameStats stats = GbaVideoFrameStats.capture(bus, frame);

        assertTrue(stats.forcedBlank());
        assertTrue(stats.objectsEnabled());
        assertEquals(127, stats.visibleObjects());
    }
}
