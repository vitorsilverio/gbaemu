package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.memory.GbaBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaVideoTest {

    private static GbaBus createBus() {
        GbaLcdTiming lcdTiming = new GbaLcdTiming(null);
        GbaBus bus = new GbaBus();
        bus.add(lcdTiming);
        bus.add(new GbaVideoMemory(() -> lcdTiming.readByte(0x04000000) & 0x7));
        return bus;
    }

    @Test
    void convertsBgr555ToOpaqueArgb() {
        assertEquals(0xFFFF0000, GbaVideo.bgr555ToArgb(0x001F));
        assertEquals(0xFF00FF00, GbaVideo.bgr555ToArgb(0x03E0));
        assertEquals(0xFF0000FF, GbaVideo.bgr555ToArgb(0x7C00));
        assertEquals(0xFFFFFFFF, GbaVideo.bgr555ToArgb(0x7FFF));
    }

    @Test
    void rendersMode3WhenBg2IsEnabled() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0403);
        bus.write16(0x06000000, 0x001F);
        bus.write16(0x06000002, 0x03E0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFFFF0000, frame[0]);
        assertEquals(0xFF00FF00, frame[1]);
    }

    @Test
    void rendersMode4ThroughPalette() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0404);
        bus.write16(0x05000004, 0x7C00);
        bus.write8(0x06000000, 2);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void rendersMode4BackBufferWhenFrameSelectIsSet() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0414);
        bus.write16(0x05000002, 0x03E0);
        bus.write8(0x0600A000, 1);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void leavesFrameBlackWhenBg2IsDisabled() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0003);
        bus.write16(0x06000000, 0x7FFF);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF000000, frame[0]);
    }

    @Test
    void rendersMode0RegularFourBppBackground() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0100);
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x05000002, 0x001F);
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000002, 0x1111);
        bus.write16(0x06000004, 0x1111);
        bus.write16(0x06000006, 0x1111);
        bus.write16(0x06000000 + 0x800, 0);
        bus.write16(0x06000000 + 0x802, 1);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFFFF0000, frame[0]);
        assertEquals(0xFFFF0000, frame[7]);
        assertEquals(0xFF000000, frame[8]);
    }

    @Test
    void regularBackgroundHonorsScroll() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0100);
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x04000010, 8);
        bus.write16(0x05000004, 0x03E0);
        bus.write16(0x06000020, 0x2222);
        bus.write16(0x06000022, 0x2222);
        bus.write16(0x06000024, 0x2222);
        bus.write16(0x06000026, 0x2222);
        bus.write16(0x06000000 + 0x800, 0);
        bus.write16(0x06000000 + 0x802, 1);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void rendersEightBppRegularBackgroundTiles() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0100);
        bus.write16(0x04000008, (1 << 8) | (1 << 7));
        bus.write16(0x05000006, 0x7C00);
        bus.write16(0x06000000, 0x0303);
        bus.write16(0x06000002, 0x0303);
        bus.write16(0x06000004, 0x0303);
        bus.write16(0x06000006, 0x0303);
        bus.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void lowerPriorityNumberDrawsAboveHigherPriorityNumber() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0300);
        bus.write16(0x04000008, (1 << 8) | 1);
        bus.write16(0x0400000A, (1 << 2) | (2 << 8));
        bus.write16(0x05000002, 0x001F);
        bus.write16(0x05000004, 0x03E0);
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000002, 0x1111);
        bus.write16(0x06004000, 0x2222);
        bus.write16(0x06004002, 0x2222);
        bus.write16(0x06000000 + 0x800, 0);
        bus.write16(0x06000000 + 0x1000, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void rendersRegularFourBppObject() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, 1 << 12);
        bus.write16(0x05000202, 0x7C00);
        bus.write16(0x06010000, 0x1111);
        bus.write16(0x06010002, 0x1111);
        bus.write16(0x06010004, 0x1111);
        bus.write16(0x06010006, 0x1111);
        bus.write16(0x07000000, 0);
        bus.write16(0x07000002, 0);
        bus.write16(0x07000004, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF0000FF, frame[0]);
        assertEquals(0xFF0000FF, frame[7]);
        assertEquals(0xFF000000, frame[8]);
    }

    @Test
    void objectPriorityIsComparedAgainstBackgroundPriority() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, (1 << 8) | (1 << 12));
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x05000002, 0x001F);
        bus.write16(0x05000202, 0x03E0);
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000002, 0x1111);
        bus.write16(0x06000000 + 0x800, 0);
        bus.write16(0x06010000, 0x1111);
        bus.write16(0x06010002, 0x1111);
        bus.write16(0x07000000, 0);
        bus.write16(0x07000002, 0);
        bus.write16(0x07000004, 1 << 10);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFFFF0000, frame[0]);
    }

    @Test
    void windowOneMasksBackgroundLayersPerPixel() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, (1 << 14) | (1 << 8));
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x04000042, (8 << 8) | 16);
        bus.write16(0x04000046, (0 << 8) | 8);
        bus.write16(0x04000048, 1 << 8);
        bus.write16(0x0400004A, 0);
        bus.write16(0x05000000, 0);
        bus.write16(0x05000002, 0x001F);
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000002, 0x1111);
        bus.write16(0x06000004, 0x1111);
        bus.write16(0x06000006, 0x1111);
        bus.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[8]);
    }

    @Test
    void windowOneCanMaskObjectsOutsideWindow() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, (1 << 14) | (1 << 12));
        bus.write16(0x04000042, (8 << 8) | 16);
        bus.write16(0x04000046, (0 << 8) | 8);
        bus.write16(0x04000048, (1 << 12));
        bus.write16(0x0400004A, 0);
        bus.write16(0x05000202, 0x001F);
        bus.write16(0x06010000, 0x1111);
        bus.write16(0x06010002, 0x1111);
        bus.write16(0x06010004, 0x1111);
        bus.write16(0x06010006, 0x1111);
        bus.write16(0x07000000, 0);
        bus.write16(0x07000002, 8);
        bus.write16(0x07000004, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[8]);
    }

    @Test
    void objectHorizontalFlipIsHonored() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, 1 << 12);
        bus.write16(0x05000202, 0x001F);
        bus.write16(0x06010000, 0x0001);
        bus.write16(0x07000000, 0);
        bus.write16(0x07000002, 1 << 12);
        bus.write16(0x07000004, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[7]);
    }

    @Test
    void eightBppObjectTileNumberUsesThirtyTwoByteUnits() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, 1 << 12);
        bus.write16(0x05000202, 0x001F);
        bus.write16(0x06010020, 0x0101);
        bus.write16(0x07000000, 1 << 13);
        bus.write16(0x07000002, 0);
        bus.write16(0x07000004, 1);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFFFF0000, frame[0]);
    }

    @Test
    void rendersMode2AffineBackgroundWithIdentityMatrix() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0402);
        bus.write16(0x0400000C, 1 << 8);
        bus.write16(0x04000020, 0x0100);
        bus.write16(0x04000026, 0x0100);
        bus.write16(0x0500000A, 0x7C00);
        bus.write16(0x06000000, 0x0505);
        bus.write16(0x06000002, 0x0505);
        bus.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF0000FF, frame[0]);
        assertEquals(0xFF0000FF, frame[1]);
    }

    @Test
    void affineBackgroundIsTransparentOutsideBoundsWhenWrapIsDisabled() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0402);
        bus.write16(0x0400000C, 1 << 8);
        bus.write16(0x04000020, 0x0100);
        bus.write16(0x04000026, 0x0100);
        bus.write32(0x04000028, 0x0FFFFF00);
        bus.write16(0x0500000A, 0x7C00);
        bus.write16(0x06000000, 0x0505);
        bus.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFF0000FF, frame[1]);
    }

    @Test
    void affineBackgroundWrapsWhenDisplayAreaOverflowIsEnabled() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0402);
        bus.write16(0x0400000C, (1 << 13) | (1 << 8));
        bus.write16(0x04000020, 0x0100);
        bus.write16(0x04000026, 0x0100);
        bus.write32(0x04000028, 0x0FFFFF00);
        bus.write16(0x0500000A, 0x7C00);
        bus.write16(0x06000000, 0x0505);
        bus.write16(0x06000002, 0x0505);
        bus.write16(0x06000004, 0x0505);
        bus.write16(0x06000006, 0x0505);
        bus.write8(0x06000000 + 0x800 + 15, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void affinePriorityParticipatesWithRegularBackgroundPriority() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 0x0501);
        bus.write16(0x04000008, (1 << 8) | 1);
        bus.write16(0x0400000C, (1 << 2) | (2 << 8));
        bus.write16(0x04000020, 0x0100);
        bus.write16(0x04000026, 0x0100);
        bus.write16(0x05000002, 0x001F);
        bus.write16(0x05000004, 0x03E0);
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000002, 0x1111);
        bus.write16(0x06000000 + 0x800, 0);
        bus.write16(0x06004000, 0x0202);
        bus.write16(0x06004002, 0x0202);
        bus.write16(0x06000000 + 0x1000, 0);

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFF00FF00, frame[0]);
    }

    private static void disableObjects(GbaBus bus) {
        for (int object = 0; object < 128; object++) {
            bus.write16(0x07000000 + object * 8, 1 << 9);
        }
    }
}
