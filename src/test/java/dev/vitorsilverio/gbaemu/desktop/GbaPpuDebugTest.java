package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaBus;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import dev.vitorsilverio.gbaemu.video.GbaVideoMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaPpuDebugTest {
    private static final int WHITE = 0xFFFFFFFF;

    @Test
    void bgr555ConvertsChannels() {
        assertEquals(0xFFFFFFFF, GbaPpuDebug.bgr555ToArgb(0x7FFF));
        assertEquals(0xFF000000, GbaPpuDebug.bgr555ToArgb(0x0000));
        assertEquals(0xFFFF0000, GbaPpuDebug.bgr555ToArgb(0x001F)); // red channel only
        assertEquals(0xFF00FF00, GbaPpuDebug.bgr555ToArgb(0x03E0)); // green channel only
        assertEquals(0xFF0000FF, GbaPpuDebug.bgr555ToArgb(0x7C00)); // blue channel only
    }

    @Test
    void charBlockDecodes4bppTileWithPaletteAndTransparency() {
        GbaBus bus = videoBus();
        bus.write16(GbaPpuDebug.BG_PALETTE + 1 * 2, 0x7FFF); // palette index 1 -> white
        bus.write32(0x06000000, 0x11111111);                // tile 0, row 0: 8 pixels of index 1

        GbaPpuDebug.DebugImage sheet = GbaPpuDebug.charBlock(bus, 0, 0, false);

        assertEquals(128, sheet.width());  // 16 tiles wide * 8
        assertEquals(256, sheet.height()); // 512 4bpp tiles / 16 cols * 8
        assertEquals(WHITE, sheet.argb()[0]);   // tile 0 (0,0): index 1 -> white
        assertEquals(0, sheet.argb()[8]);        // tile 1 (0,0): index 0 -> transparent
    }

    @Test
    void backgroundLayerDecodesTextMapSizeAndPixels() {
        GbaBus bus = videoBus();
        bus.write16(0x04000000, 0x0100);            // DISPCNT: mode 0, BG0 enabled
        bus.write16(0x04000008, 0x0100);            // BG0CNT: charBlk 0, scrBlk 1, 4bpp, size 0
        bus.write16(GbaPpuDebug.BG_PALETTE + 1 * 2, 0x7FFF); // index 1 -> white
        bus.write32(0x06000020, 0x11111111);        // tile 1 row 0: index 1 pixels
        bus.write16(0x06000800, 0x0001);            // screenblock 1 entry (0,0) -> tile 1

        GbaPpuDebug.DebugImage layer = GbaPpuDebug.backgroundLayer(bus, 0);

        assertEquals(256, layer.width());
        assertEquals(256, layer.height());
        assertEquals(WHITE, layer.argb()[0]); // map pixel (0,0) -> tile 1, index 1 -> white
    }

    @Test
    void backgroundLayerIsNullForUnusedLayerInMode() {
        GbaBus bus = videoBus();
        bus.write16(0x04000000, 0x0002); // mode 2: only BG2/BG3 usable

        assertEquals(null, GbaPpuDebug.backgroundLayer(bus, 0));
        assertEquals(null, GbaPpuDebug.backgroundLayer(bus, 1));
    }

    private static GbaBus videoBus() {
        GbaBus bus = new GbaBus();
        bus.add(new GbaLcdTiming(new GbaInterruptController()));
        bus.add(new GbaVideoMemory(() -> 0));
        return bus;
    }
}
