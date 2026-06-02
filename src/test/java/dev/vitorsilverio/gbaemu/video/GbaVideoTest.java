package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaVideoTest {
    @Test
    void convertsBgr555ToOpaqueArgb() {
        assertEquals(0xFFFF0000, GbaVideo.bgr555ToArgb(0x001F));
        assertEquals(0xFF00FF00, GbaVideo.bgr555ToArgb(0x03E0));
        assertEquals(0xFF0000FF, GbaVideo.bgr555ToArgb(0x7C00));
        assertEquals(0xFFFFFFFF, GbaVideo.bgr555ToArgb(0x7FFF));
    }

    @Test
    void rendersMode3WhenBg2IsEnabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0403);
        memory.write16(0x06000000, 0x001F);
        memory.write16(0x06000002, 0x03E0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFFFF0000, frame[0]);
        assertEquals(0xFF00FF00, frame[1]);
    }

    @Test
    void rendersMode4ThroughPalette() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0404);
        memory.write16(0x05000004, 0x7C00);
        memory.write8(0x06000000, 2);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void rendersMode4BackBufferWhenFrameSelectIsSet() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0414);
        memory.write16(0x05000002, 0x03E0);
        memory.write8(0x0600A000, 1);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void leavesFrameBlackWhenBg2IsDisabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0003);
        memory.write16(0x06000000, 0x7FFF);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF000000, frame[0]);
    }

    @Test
    void rendersMode0RegularFourBppBackground() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0100);
        memory.write16(0x04000008, 1 << 8);
        memory.write16(0x05000002, 0x001F);
        memory.write16(0x06000000, 0x1111);
        memory.write16(0x06000002, 0x1111);
        memory.write16(0x06000004, 0x1111);
        memory.write16(0x06000006, 0x1111);
        memory.write16(0x06000000 + 0x800, 0);
        memory.write16(0x06000000 + 0x802, 1);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFFFF0000, frame[0]);
        assertEquals(0xFFFF0000, frame[7]);
        assertEquals(0xFF000000, frame[8]);
    }

    @Test
    void regularBackgroundHonorsScroll() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0100);
        memory.write16(0x04000008, 1 << 8);
        memory.write16(0x04000010, 8);
        memory.write16(0x05000004, 0x03E0);
        memory.write16(0x06000020, 0x2222);
        memory.write16(0x06000022, 0x2222);
        memory.write16(0x06000024, 0x2222);
        memory.write16(0x06000026, 0x2222);
        memory.write16(0x06000000 + 0x800, 0);
        memory.write16(0x06000000 + 0x802, 1);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void rendersEightBppRegularBackgroundTiles() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0100);
        memory.write16(0x04000008, (1 << 8) | (1 << 7));
        memory.write16(0x05000006, 0x7C00);
        memory.write16(0x06000000, 0x0303);
        memory.write16(0x06000002, 0x0303);
        memory.write16(0x06000004, 0x0303);
        memory.write16(0x06000006, 0x0303);
        memory.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void lowerPriorityNumberDrawsAboveHigherPriorityNumber() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0300);
        memory.write16(0x04000008, (1 << 8) | 1);
        memory.write16(0x0400000A, (1 << 2) | (2 << 8));
        memory.write16(0x05000002, 0x001F);
        memory.write16(0x05000004, 0x03E0);
        memory.write16(0x06000000, 0x1111);
        memory.write16(0x06000002, 0x1111);
        memory.write16(0x06004000, 0x2222);
        memory.write16(0x06004002, 0x2222);
        memory.write16(0x06000000 + 0x800, 0);
        memory.write16(0x06000000 + 0x1000, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF00FF00, frame[0]);
    }

    @Test
    void rendersRegularFourBppObject() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        disableObjects(memory);
        memory.write16(0x04000000, 1 << 12);
        memory.write16(0x05000202, 0x7C00);
        memory.write16(0x06010000, 0x1111);
        memory.write16(0x06010002, 0x1111);
        memory.write16(0x06010004, 0x1111);
        memory.write16(0x06010006, 0x1111);
        memory.write16(0x07000000, 0);
        memory.write16(0x07000002, 0);
        memory.write16(0x07000004, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF0000FF, frame[0]);
        assertEquals(0xFF0000FF, frame[7]);
        assertEquals(0xFF000000, frame[8]);
    }

    @Test
    void objectPriorityIsComparedAgainstBackgroundPriority() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        disableObjects(memory);
        memory.write16(0x04000000, (1 << 8) | (1 << 12));
        memory.write16(0x04000008, 1 << 8);
        memory.write16(0x05000002, 0x001F);
        memory.write16(0x05000202, 0x03E0);
        memory.write16(0x06000000, 0x1111);
        memory.write16(0x06000002, 0x1111);
        memory.write16(0x06000000 + 0x800, 0);
        memory.write16(0x06010000, 0x1111);
        memory.write16(0x06010002, 0x1111);
        memory.write16(0x07000000, 0);
        memory.write16(0x07000002, 0);
        memory.write16(0x07000004, 1 << 10);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFFFF0000, frame[0]);
    }

    @Test
    void windowOneMasksBackgroundLayersPerPixel() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, (1 << 14) | (1 << 8));
        memory.write16(0x04000008, 1 << 8);
        memory.write16(0x04000042, (8 << 8) | 16);
        memory.write16(0x04000046, (0 << 8) | 8);
        memory.write16(0x04000048, 1 << 8);
        memory.write16(0x0400004A, 0);
        memory.write16(0x05000000, 0);
        memory.write16(0x05000002, 0x001F);
        memory.write16(0x06000000, 0x1111);
        memory.write16(0x06000002, 0x1111);
        memory.write16(0x06000004, 0x1111);
        memory.write16(0x06000006, 0x1111);
        memory.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[8]);
    }

    @Test
    void windowOneCanMaskObjectsOutsideWindow() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        disableObjects(memory);
        memory.write16(0x04000000, (1 << 14) | (1 << 12));
        memory.write16(0x04000042, (8 << 8) | 16);
        memory.write16(0x04000046, (0 << 8) | 8);
        memory.write16(0x04000048, (1 << 12));
        memory.write16(0x0400004A, 0);
        memory.write16(0x05000202, 0x001F);
        memory.write16(0x06010000, 0x1111);
        memory.write16(0x06010002, 0x1111);
        memory.write16(0x06010004, 0x1111);
        memory.write16(0x06010006, 0x1111);
        memory.write16(0x07000000, 0);
        memory.write16(0x07000002, 8);
        memory.write16(0x07000004, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[8]);
    }

    @Test
    void objectHorizontalFlipIsHonored() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        disableObjects(memory);
        memory.write16(0x04000000, 1 << 12);
        memory.write16(0x05000202, 0x001F);
        memory.write16(0x06010000, 0x0001);
        memory.write16(0x07000000, 0);
        memory.write16(0x07000002, 1 << 12);
        memory.write16(0x07000004, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFFFF0000, frame[7]);
    }

    @Test
    void eightBppObjectTileNumberUsesThirtyTwoByteUnits() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        disableObjects(memory);
        memory.write16(0x04000000, 1 << 12);
        memory.write16(0x05000202, 0x001F);
        memory.write16(0x06010020, 0x0101);
        memory.write16(0x07000000, 1 << 13);
        memory.write16(0x07000002, 0);
        memory.write16(0x07000004, 1);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFFFF0000, frame[0]);
    }

    @Test
    void rendersMode2AffineBackgroundWithIdentityMatrix() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0402);
        memory.write16(0x0400000C, 1 << 8);
        memory.write16(0x04000020, 0x0100);
        memory.write16(0x04000026, 0x0100);
        memory.write16(0x0500000A, 0x7C00);
        memory.write16(0x06000000, 0x0505);
        memory.write16(0x06000002, 0x0505);
        memory.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF0000FF, frame[0]);
        assertEquals(0xFF0000FF, frame[1]);
    }

    @Test
    void affineBackgroundIsTransparentOutsideBoundsWhenWrapIsDisabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0402);
        memory.write16(0x0400000C, 1 << 8);
        memory.write16(0x04000020, 0x0100);
        memory.write16(0x04000026, 0x0100);
        memory.write32(0x04000028, 0x0FFFFF00);
        memory.write16(0x0500000A, 0x7C00);
        memory.write16(0x06000000, 0x0505);
        memory.write16(0x06000000 + 0x800, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF000000, frame[0]);
        assertEquals(0xFF0000FF, frame[1]);
    }

    @Test
    void affineBackgroundWrapsWhenDisplayAreaOverflowIsEnabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0402);
        memory.write16(0x0400000C, (1 << 13) | (1 << 8));
        memory.write16(0x04000020, 0x0100);
        memory.write16(0x04000026, 0x0100);
        memory.write32(0x04000028, 0x0FFFFF00);
        memory.write16(0x0500000A, 0x7C00);
        memory.write16(0x06000000, 0x0505);
        memory.write16(0x06000002, 0x0505);
        memory.write16(0x06000004, 0x0505);
        memory.write16(0x06000006, 0x0505);
        memory.write8(0x06000000 + 0x800 + 15, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF0000FF, frame[0]);
    }

    @Test
    void affinePriorityParticipatesWithRegularBackgroundPriority() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaVideo video = new GbaVideo();

        memory.write16(0x04000000, 0x0501);
        memory.write16(0x04000008, (1 << 8) | 1);
        memory.write16(0x0400000C, (1 << 2) | (2 << 8));
        memory.write16(0x04000020, 0x0100);
        memory.write16(0x04000026, 0x0100);
        memory.write16(0x05000002, 0x001F);
        memory.write16(0x05000004, 0x03E0);
        memory.write16(0x06000000, 0x1111);
        memory.write16(0x06000002, 0x1111);
        memory.write16(0x06000000 + 0x800, 0);
        memory.write16(0x06004000, 0x0202);
        memory.write16(0x06004002, 0x0202);
        memory.write16(0x06000000 + 0x1000, 0);

        int[] frame = video.renderFrame(memory);

        assertEquals(0xFF00FF00, frame[0]);
    }

    private static void disableObjects(GbaMemory memory) {
        for (int object = 0; object < 128; object++) {
            memory.write16(0x07000000 + object * 8, 1 << 9);
        }
    }
}
