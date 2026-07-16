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

    @Test
    void affineObjectWithIdentityMatrixRendersFullSquare() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, 1 << 12); // OBJ enable, mode 0
        bus.write16(0x05000202, 0x001F);  // OBJ palette index 1 = red
        for (int word = 0; word < 8; word++) {
            bus.write32(0x06010000 + word * 4, 0x11111111); // tile 0: 8x8 of palette index 1
        }
        bus.write16(0x07000000, 1 << 8);  // OBJ0 attr0: affine, y=0, shape 0 (square), 8x8
        bus.write16(0x07000002, 0);       // attr1: x=0, matrix 0, size 0
        bus.write16(0x07000004, 0);       // attr2: tile 0, palette 0
        // affine matrix 0 = identity (PA/PD = 1.0 in 8.8, PB/PC = 0)
        bus.write16(0x07000006, 0x0100);
        bus.write16(0x0700000E, 0x0000);
        bus.write16(0x07000016, 0x0000);
        bus.write16(0x0700001E, 0x0100);

        int[] frame = video.renderFrame(bus);

        // The whole 8x8 must be red, not collapsed onto a diagonal line.
        assertEquals(0xFFFF0000, frame[0]);
        assertEquals(0xFFFF0000, frame[7]);
        assertEquals(0xFFFF0000, frame[7 * GbaVideo.WIDTH]);
        assertEquals(0xFFFF0000, frame[7 * GbaVideo.WIDTH + 7]);
    }

    @Test
    void doubleSizeAffineObjectWithIdentityMatrixRendersCentered() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, 1 << 12);
        bus.write16(0x05000202, 0x001F);
        for (int word = 0; word < 8; word++) {
            bus.write32(0x06010000 + word * 4, 0x11111111);
        }
        bus.write16(0x07000000, (1 << 8) | (1 << 9)); // affine + double-size, 8x8 texture -> 16x16 area
        bus.write16(0x07000002, 0);
        bus.write16(0x07000004, 0);
        bus.write16(0x07000006, 0x0100);
        bus.write16(0x0700000E, 0x0000);
        bus.write16(0x07000016, 0x0000);
        bus.write16(0x0700001E, 0x0100);

        int[] frame = video.renderFrame(bus);

        // 8x8 texture sits in the centre of the 16x16 render area (screen 4..11).
        assertEquals(0xFF000000, frame[0]);                         // corner of render area = transparent
        assertEquals(0xFFFF0000, frame[4 * GbaVideo.WIDTH + 4]);    // centre top-left of the sprite
        assertEquals(0xFFFF0000, frame[11 * GbaVideo.WIDTH + 11]);  // centre bottom-right of the sprite
    }

    @Test
    void nonDoubleSize64x64AffineIdentityRendersBottomRows(/* the rival's feet */) {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, (1 << 12) | (1 << 6)); // OBJ on, 1D mapping, mode 0
        bus.write16(0x05000202, 0x001F);               // OBJ palette[1] = red
        for (int word = 0; word < 64 * 32 / 4; word++) {
            bus.write32(0x06010000 + word * 4, 0x11111111); // tiles 0..63 = all index 1
        }
        bus.write16(0x07000000, 1 << 8);     // attr0: affine, y=0, shape 0, NOT double-size
        bus.write16(0x07000002, 3 << 14);    // attr1: x=0, matrix 0, size 3 -> 64x64
        bus.write16(0x07000004, 0);          // attr2: tile 0, prio 0, pal 0
        bus.write16(0x07000006, 0x0100);     // identity matrix
        bus.write16(0x0700000E, 0x0000);
        bus.write16(0x07000016, 0x0000);
        bus.write16(0x0700001E, 0x0100);

        int[] frame = video.renderFrame(bus);

        // The bottom-right pixel of the 64x64 sprite (the "feet") must render, not clip.
        assertEquals(0xFFFF0000, frame[63 * GbaVideo.WIDTH + 63]);
        assertEquals(0xFFFF0000, frame[63 * GbaVideo.WIDTH + 0]);
    }

    @Test
    void brightnessIncreaseFadeLightensTheFirstTargetLayer() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 1 << 8);     // BG0 on, mode 0
        bus.write16(0x04000008, 1 << 8);     // BG0CNT: screenBlock 1, prio 0
        bus.write16(0x05000002, 0x4210);     // palette[1] = grey (16,16,16)
        bus.write16(0x06000000, 0x1111);     // tile 0 -> index 1
        bus.write16(0x06000800, 0);          // tilemap entry 0
        bus.write16(0x04000050, (2 << 6) | 1); // BLDCNT: mode 2 (brighten), 1st target BG0
        bus.write16(0x04000054, 8);          // BLDY = 8/16

        int[] frame = video.renderFrame(bus);

        // 16 + (31-16)*8/16 = 23 -> 0xBD per channel
        assertEquals(0xFFBDBDBD, frame[0]);
    }

    @Test
    void brightnessDecreaseFadeDarkensTheFirstTargetLayer() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 1 << 8);
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x05000002, 0x4210);     // grey (16,16,16)
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000800, 0);
        bus.write16(0x04000050, (3 << 6) | 1); // mode 3 (darken), 1st target BG0
        bus.write16(0x04000054, 8);

        int[] frame = video.renderFrame(bus);

        // 16 - 16*8/16 = 8 -> 0x42 per channel
        assertEquals(0xFF424242, frame[0]);
    }

    @Test
    void alphaBlendMixesFirstAndSecondTargets() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        bus.write16(0x04000000, 1 << 8);
        bus.write16(0x04000008, 1 << 8);
        bus.write16(0x05000000, 0x0000);     // backdrop = black (2nd target)
        bus.write16(0x05000002, 0x7FFF);     // BG0 = white (1st target)
        bus.write16(0x06000000, 0x1111);
        bus.write16(0x06000800, 0);
        bus.write16(0x04000050, (1 << 6) | 1 | (1 << 13)); // mode 1, 1st=BG0, 2nd=backdrop
        bus.write16(0x04000052, (8 << 8) | 8);             // EVA=8/16, EVB=8/16

        int[] frame = video.renderFrame(bus);

        // (31*8 + 0*8)/16 = 15 -> 0x7B per channel
        assertEquals(0xFF7B7B7B, frame[0]);
    }

    @Test
    void semiTransparentObjectBlendsWithBackgroundBelow() {
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, (1 << 8) | (1 << 12)); // BG0 + OBJ, mode 0
        bus.write16(0x04000008, 1 << 8);               // BG0CNT prio 0
        bus.write16(0x05000002, 0x0000);               // BG palette[1] = black (the 2nd target)
        bus.write16(0x06000000, 0x1111);               // BG0 tile -> black
        bus.write16(0x06000800, 0);
        bus.write16(0x05000202, 0x7FFF);               // OBJ palette[1] = white
        bus.write16(0x06010000, 0x1111);               // OBJ tile 0 -> white
        bus.write16(0x07000000, 1 << 10);              // OBJ0 attr0: y=0, objmode=1 (semi-transparent)
        bus.write16(0x07000002, 0);                    // attr1: x=0
        bus.write16(0x07000004, 0);                    // attr2: tile 0, priority 0 (on top of BG0)
        bus.write16(0x04000050, 1 << (8 + 0));         // 2nd target = BG0
        bus.write16(0x04000052, (8 << 8) | 8);         // EVA=8/16, EVB=8/16

        int[] frame = video.renderFrame(bus);

        // Semi-transparent OBJ (white) blends with BG0 below (black): (31*8 + 0*8)/16 = 15.
        assertEquals(0xFF7B7B7B, frame[0]);
    }

    @Test
    void objWindowModeSpriteMasksBlendWithoutBeingDrawnItself() {
        // D2 hipótese 3: FireRed's battle stat up/down overlay is an OBJ-mode-2 ("OBJ window")
        // sprite whose opaque texels define a region where BLDCNT blending is allowed, elsewhere
        // suppressed. The sprite itself must never appear as a visible pixel.
        GbaBus bus = createBus();
        GbaVideo video = new GbaVideo();

        disableObjects(bus);
        bus.write16(0x04000000, (1 << 15) | (1 << 12)); // OBJ window enable + OBJ enable, mode 0
        bus.write16(0x05000000, 0x001F); // backdrop = red
        bus.write16(0x04000050, 0xA0);   // BLDCNT: mode=brighten(2), 1st target=backdrop(bit5)
        bus.write16(0x04000054, 16);     // BLDY: EVY=16/16 -> full brighten to white
        bus.write16(0x0400004A, 1 << 13); // WINOUT: outside blend off, inside OBJ window blend on

        // OBJ0: mode=2 (OBJ window), 8x8 square at x=8, one opaque texel.
        bus.write16(0x07000000, 2 << 10);
        bus.write16(0x07000002, 8);
        bus.write16(0x07000004, 0);
        bus.write16(0x06010000, 0x0001); // OBJ VRAM ignores 8-bit writes on hardware

        int[] frame = video.renderFrame(bus);

        assertEquals(0xFFFF0000, frame[0]); // outside the OBJ window: blend suppressed, red backdrop
        assertEquals(0xFFFFFFFF, frame[8]); // inside: blend allowed, brightened to white
    }

    private static void disableObjects(GbaBus bus) {
        for (int object = 0; object < 128; object++) {
            bus.write16(0x07000000 + object * 8, 1 << 9);
        }
    }
}
