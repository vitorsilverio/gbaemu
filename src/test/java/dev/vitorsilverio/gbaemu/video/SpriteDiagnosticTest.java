package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.desktop.GbaFrameImage;
import dev.vitorsilverio.gbaemu.input.GbaButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Opt-in (-Dsprite.diag=1) visual probe: mashes A to drive FireRed past the title into
/// the New Game / Prof. Oak intro, and at each checkpoint writes the composed frame plus
/// each layer in isolation (bg0..bg3, obj) to target/sprite-diag-*.png, and reports the
/// active OAM sprites. Used to debug background priority / affine / sprite compositing.
class SpriteDiagnosticTest {
    private static final int CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;
    private static final int DISPCNT = 0x04000000;
    private static final int OAM = 0x07000000;

    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void driveIntoIntroAndCaptureLayers() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true); // capture the per-scanline-accurate frame

        runFrames(console, 360);
        capture(console, 0, "title-idle");
        for (int round = 1; round <= 60; round++) {
            tap(console, GbaButton.A, 6, 30);
            capture(console, round, "after-A-" + round);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void realBiosSwiSmokeTest() {
        Path biosPath = Path.of("gba_bios.bin");
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(biosPath) && Files.exists(romPath), "bios/rom not present");
        GbaConsole console;
        try {
            console = GbaConsole.fromBiosAndRomRealSwi(Files.readAllBytes(biosPath), Files.readAllBytes(romPath));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        console.setScanlineRenderingEnabled(true);
        // Drive into the Oak intro with the REAL BIOS handling SWIs, and capture frames.
        // If Oak's portrait now renders (instead of green), our HLE SWI was the culprit.
        runFrames(console, 360);
        writePng(console.currentFrame(), "realswi-00.png");
        for (int round = 1; round <= 45; round++) {
            tap(console, GbaButton.A, 6, 30);
            writePng(console.currentFrame(), String.format("realswi-%02d.png", round));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void biosBootCapturesTheAffineLogoAnimation() {
        Path biosPath = Path.of("gba_bios.bin");
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(biosPath) && Files.exists(romPath), "bios/rom not present");
        GbaConsole console;
        try {
            console = GbaConsole.fromBiosAndRom(Files.readAllBytes(biosPath), Files.readAllBytes(romPath));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        console.setScanlineRenderingEnabled(true);
        for (int frame = 0; frame <= 200; frame += 10) {
            runFrames(console, 10);
            writePng(console.currentFrame(), String.format("bios-boot-%03d.png", frame));
        }
    }

    /// Navigates the FireRed intro and dumps the compositing-relevant registers (scroll,
    /// affine refs, windows, blending) for every mode-1 scene (Oak / character), so we can
    /// see why BG1/BG2 are not appearing on screen even though their VRAM is correct.
    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void dumpIntroCompositingRegisters() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true);
        runFrames(console, 360);
        tap(console, GbaButton.START, 6, 40); // enter past the title screen
        for (int round = 1; round <= 60; round++) {
            tap(console, GbaButton.A, 6, 18);
            dumpCompositing(console, "after-A-" + round);
        }
        int[] counts = dev.vitorsilverio.gbaemu.bios.GbaBiosSwi.callCountsSnapshot();
        StringBuilder swi = new StringBuilder("SWI call counts:");
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                swi.append(String.format(" %02X=%d", i, counts[i]));
            }
        }
        System.out.println(swi);
    }

    /// Steps the intro frame-by-frame and logs every change to the BG2 affine registers
    /// (PA-PD, X, Y) with the PC, to find out whether FireRed ever writes a valid matrix
    /// (game's direct register writes) or it stays zero (a CPU/execution bug upstream).
    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void traceBg2AffineWrites() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true);
        int[] previous = null;
        int changes = 0;
        for (int frame = 0; frame < 1600 && changes < 80; frame++) {
            if (frame == 360) console.keypad().setPressed(GbaButton.START, true);
            if (frame == 366) console.keypad().setPressed(GbaButton.START, false);
            if (frame > 380) {
                console.keypad().setPressed(GbaButton.A, ((frame / 8) % 2) == 0); // pulse A
            }
            console.runCycles(CYCLES_PER_FRAME);
            int[] current = bg2Affine(console);
            if (previous == null || !java.util.Arrays.equals(current, previous)) {
                int dispcnt = console.bus().read16(0x04000000);
                System.out.printf("frame %4d PC=%08X mode=%d BG2on=%b  PA=%04X PB=%04X PC=%04X PD=%04X X=%08X Y=%08X%n",
                        frame, console.cpu().programCounter(), dispcnt & 7, (dispcnt & (1 << 10)) != 0,
                        current[0], current[1], current[2], current[3], current[4], current[5]);
                previous = current;
                changes++;
            }
        }
    }

    /// Navigates to just before the Oak scene, then steps in small instruction chunks
    /// through the transition, reporting if/when BG2 PA or PD ever leave zero (with the
    /// approximate PC). Catches a sub-frame write that per-frame sampling would miss.
    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void findBg2MatrixWrite() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true);
        for (int frame = 0; frame < 1000; frame++) {
            if (frame == 360) console.keypad().setPressed(GbaButton.START, true);
            if (frame == 366) console.keypad().setPressed(GbaButton.START, false);
            if (frame > 380) {
                console.keypad().setPressed(GbaButton.A, ((frame / 8) % 2) == 0);
            }
            console.runCycles(CYCLES_PER_FRAME);
        }
        console.keypad().setPressed(GbaButton.A, false);

        int prevPa = console.bus().read16(0x04000020);
        int prevPd = console.bus().read16(0x04000026);
        boolean foundNonZero = false;
        for (int chunk = 0; chunk < 120_000 && !foundNonZero; chunk++) {
            console.stepCpu(64);
            int pa = console.bus().read16(0x04000020);
            int pd = console.bus().read16(0x04000026);
            if (pa != prevPa || pd != prevPd) {
                System.out.printf("chunk %d: BG2PA %04X->%04X  BG2PD %04X->%04X  near PC=%08X DISPCNT=%04X%n",
                        chunk, prevPa, pa, prevPd, pd, console.cpu().programCounter(),
                        console.bus().read16(0x04000000));
                prevPa = pa;
                prevPd = pd;
                foundNonZero = pa != 0 || pd != 0;
            }
        }
        System.out.printf("FINAL BG2PA=%04X BG2PD=%04X (foundNonZero=%b)%n",
                console.bus().read16(0x04000020), console.bus().read16(0x04000026), foundNonZero);
    }

    /// Polls BG0's tile 0 (0x06008000) frame-by-frame through the intro, logging every
    /// change, to find when/why it becomes the opaque-green fill (0x66666666) that covers
    /// Oak — whereas mGBA keeps it blank (0x00000000).
    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void traceBg0Tile0() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true);
        int previous = ~0;
        for (int frame = 0; frame < 1500; frame++) {
            if (frame == 360) console.keypad().setPressed(GbaButton.START, true);
            if (frame == 366) console.keypad().setPressed(GbaButton.START, false);
            if (frame > 380) {
                console.keypad().setPressed(GbaButton.A, ((frame / 8) % 2) == 0);
            }
            console.runCycles(CYCLES_PER_FRAME);
            int tile0 = console.bus().read32(0x06008000);
            if (tile0 != previous) {
                int dispcnt = console.bus().read16(0x04000000);
                System.out.printf("frame %4d PC=%08X DISPCNT=%04X mode=%d BG0CNT=%04X  tile0@06008000=%08X%n",
                        frame, console.cpu().programCounter(), dispcnt, dispcnt & 7,
                        console.bus().read16(0x04000008), tile0);
                previous = tile0;
            }
        }
    }

    /// Navigates to just before BG0 tile 0 turns green, then single-steps in small chunks
    /// to pin the approximate PC of the instruction that writes 0x66666666 to 0x06008000.
    @Test
    @EnabledIfSystemProperty(named = "sprite.diag", matches = "1")
    void findBg0Tile0Write() {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = readRom(romPath);
        console.setScanlineRenderingEnabled(true);
        for (int frame = 0; frame < 358; frame++) {
            if (frame == 356) console.keypad().setPressed(GbaButton.START, true);
            console.runCycles(CYCLES_PER_FRAME);
        }
        System.out.printf("pre-step: tile0@06008000=%08X%n", console.bus().read32(0x06008000));
        int prev = console.bus().read32(0x06008000);
        for (int step = 0; step < 600_000; step++) {
            console.stepCpu(1);
            int tile0 = console.bus().read32(0x06008000);
            if (tile0 != prev) {
                int[] r = console.cpu().registersSnapshot();
                System.out.printf("step %d: tile0 %08X -> %08X  PC(next)=%08X%n", step, prev, tile0, console.cpu().programCounter());
                System.out.printf("  r0=%08X r1=%08X r2=%08X r3=%08X r4=%08X r5=%08X r6=%08X r7=%08X%n",
                        r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
                System.out.printf("  r8=%08X r9=%08X r10=%08X r11=%08X r12=%08X sp=%08X lr=%08X pc=%08X%n",
                        r[8], r[9], r[10], r[11], r[12], r[13], r[14], r[15]);
                var bus = console.bus();
                System.out.printf("  DMA3 SAD=%08X DAD=%08X CNT_L=%04X CNT_H=%04X%n",
                        bus.read32(0x040000D4), bus.read32(0x040000D8), bus.read16(0x040000DC), bus.read16(0x040000DE));
                System.out.printf("  src@02005B30 = %08X %08X %08X %08X%n",
                        bus.read32(0x02005B30), bus.read32(0x02005B34), bus.read32(0x02005B38), bus.read32(0x02005B3C));
                prev = tile0;
                if (tile0 == 0x66666666) {
                    break;
                }
            }
        }
    }

    private static int[] bg2Affine(GbaConsole console) {
        var bus = console.bus();
        int dispcnt = bus.read16(0x04000000);
        return new int[]{
                bus.read16(0x04000020), bus.read16(0x04000022), bus.read16(0x04000024),
                bus.read16(0x04000026), bus.read32(0x04000028), bus.read32(0x0400002C),
                dispcnt & 7, (dispcnt >> 10) & 1 // mode + BG2-enable, so scene changes log too
        };
    }

    private static void dumpCompositing(GbaConsole console, String label) {
        var bus = console.bus();
        int dispcnt = bus.read16(0x04000000);
        if ((dispcnt & 0x7) != 1 || (dispcnt & (1 << 10)) == 0) {
            return; // only mode-1 scenes with BG2 enabled (Oak / character portraits)
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-12s DISPCNT=%04X mode=%d obj=%b win0=%b win1=%b objwin=%b backdrop=%04X%n",
                label, dispcnt, dispcnt & 7, (dispcnt & (1 << 12)) != 0,
                (dispcnt & (1 << 13)) != 0, (dispcnt & (1 << 14)) != 0, (dispcnt & (1 << 15)) != 0,
                bus.read16(0x05000000)));
        for (int bg = 0; bg < 4; bg++) {
            int cnt = bus.read16(0x04000008 + bg * 2);
            out.append(String.format("   BG%d %s prio=%d charBlk=%d scrBlk=%d 8bpp=%b size=%d hofs=%d vofs=%d%n",
                    bg, (dispcnt & (1 << (8 + bg))) != 0 ? "ON " : "off", cnt & 3, (cnt >> 2) & 3,
                    (cnt >> 8) & 0x1F, (cnt & 0x80) != 0, (cnt >> 14) & 3,
                    bus.read16(0x04000010 + bg * 4) & 0x1FF, bus.read16(0x04000012 + bg * 4) & 0x1FF));
        }
        out.append(String.format("   BG2aff PA=%04X PB=%04X PC=%04X PD=%04X X=%08X Y=%08X%n",
                bus.read16(0x04000020), bus.read16(0x04000022), bus.read16(0x04000024),
                bus.read16(0x04000026), bus.read32(0x04000028), bus.read32(0x0400002C)));
        out.append(String.format("   BLDCNT=%04X BLDALPHA=%04X BLDY=%04X WIN0H=%04X WIN0V=%04X WIN1H=%04X WIN1V=%04X WININ=%04X WINOUT=%04X%n",
                bus.read16(0x04000050), bus.read16(0x04000052), bus.read16(0x04000054),
                bus.read16(0x04000040), bus.read16(0x04000044), bus.read16(0x04000042),
                bus.read16(0x04000046), bus.read16(0x04000048), bus.read16(0x0400004A)));
        int bg0cnt = bus.read16(0x04000008);
        int bg0char = 0x06000000 + ((bg0cnt >> 2) & 3) * 0x4000;
        int bg0screen = 0x06000000 + ((bg0cnt >> 8) & 0x1F) * 0x800;
        out.append(String.format("   BG0 charBase=%08X scrBase=%08X map[0..3]=%04X %04X %04X %04X tile0=%08X %08X pal[bank13,idx6]=%04X%n",
                bg0char, bg0screen,
                bus.read16(bg0screen), bus.read16(bg0screen + 2), bus.read16(bg0screen + 4), bus.read16(bg0screen + 6),
                bus.read32(bg0char), bus.read32(bg0char + 4),
                bus.read16(0x05000000 + (13 * 16 + 6) * 2)));
        System.out.print(out);
        writePng(console.currentFrame(), "oak-" + label + ".png");
        // Each BG in isolation, to see which layer covers Oak in the composite.
        isolate(console, dispcnt, (dispcnt & 0x7) | (1 << 8), "oak-bg0only-" + label + ".png");
        isolate(console, dispcnt, (dispcnt & 0x7) | (1 << 9), "oak-bg1only-" + label + ".png");
        isolate(console, dispcnt, (dispcnt & 0x7) | (1 << 10), "oak-bg2only-" + label + ".png");
    }

    private static GbaConsole readRom(Path romPath) {
        try {
            return GbaConsole.fromRom(Files.readAllBytes(romPath));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void tap(GbaConsole console, GbaButton button, int holdFrames, int releaseFrames) {
        console.keypad().setPressed(button, true);
        runFrames(console, holdFrames);
        console.keypad().setPressed(button, false);
        runFrames(console, releaseFrames);
    }

    private static int[] dims(int shape, int size) {
        int[][][] table = {
                {{8, 8}, {16, 16}, {32, 32}, {64, 64}},
                {{16, 8}, {32, 8}, {32, 16}, {64, 32}},
                {{8, 16}, {8, 32}, {16, 32}, {32, 64}},
                {{8, 8}, {8, 8}, {8, 8}, {8, 8}}
        };
        return table[shape][size];
    }

    private static void runFrames(GbaConsole console, int frames) {
        for (int i = 0; i < frames; i++) {
            console.runCycles(CYCLES_PER_FRAME);
        }
    }

    private static void capture(GbaConsole console, int index, String label) {
        int dispcnt = console.bus().read16(DISPCNT);
        // The composed frame as accumulated per-scanline during emulation (accurate).
        writePng(console.currentFrame(), String.format("sprite-diag-%02d-%s.png", index, label));

        // Each BG/OBJ layer alone, to see which layer holds what and how it composites.
        int base = dispcnt & ~((0xF << 8) | (1 << 12));
        for (int layer = 0; layer < 4; layer++) {
            isolate(console, dispcnt, base | (1 << (8 + layer)),
                    String.format("sprite-diag-%02d-bg%d.png", index, layer));
        }
        isolate(console, dispcnt, base | (1 << 12), String.format("sprite-diag-%02d-obj.png", index));

        int active = 0;
        StringBuilder objs = new StringBuilder();
        for (int object = 0; object < 128; object++) {
            int attr0 = console.bus().read16(OAM + object * 8);
            int attr1 = console.bus().read16(OAM + object * 8 + 2);
            int attr2 = console.bus().read16(OAM + object * 8 + 4);
            boolean affine = (attr0 & (1 << 8)) != 0;
            if (!affine && (attr0 & (1 << 9)) != 0) {
                continue; // disabled
            }
            int y = attr0 & 0xFF;
            if (y >= 160) y -= 256;
            int x = attr1 & 0x1FF;
            if (x >= 256) x -= 512;
            if (x > -64 && x < 240 && y > -64 && y < 160) {
                active++;
                int[] wh = dims((attr0 >>> 14) & 3, (attr1 >>> 14) & 3);
                String aff = "";
                if (affine) {
                    int matrix = (attr1 >>> 9) & 0x1F;
                    int mb = OAM + matrix * 32;
                    aff = String.format(" AFF double=%b PA=%04X PB=%04X PC=%04X PD=%04X",
                            (attr0 & (1 << 9)) != 0, console.bus().read16(mb + 6), console.bus().read16(mb + 14),
                            console.bus().read16(mb + 22), console.bus().read16(mb + 30));
                }
                if (active <= 8) {
                    objs.append(String.format(" [o%d %dx%d x=%d y=%d prio=%d mode=%d%s]",
                            object, wh[0], wh[1], x, y, (attr2 >>> 10) & 3, (attr0 >>> 10) & 3, aff));
                }
            }
        }
        StringBuilder bgPrio = new StringBuilder();
        for (int bg = 0; bg < 4; bg++) {
            int cnt = console.bus().read16(0x04000008 + bg * 2);
            boolean on = (dispcnt & (1 << (8 + bg))) != 0;
            bgPrio.append(String.format(" BG%d(%s,p%d)", bg, on ? "on" : "off", cnt & 3));
        }
        System.out.printf("%-16s DISPCNT=%04X mode=%d BLDCNT=%04X |%s | act=%d%s%n",
                label, dispcnt, dispcnt & 0x7, console.bus().read16(0x04000050),
                bgPrio, active, objs);

        if (index >= 36 && index <= 39) {
            for (int bg = 0; bg < 3; bg++) {
                int cnt = console.bus().read16(0x04000008 + bg * 2);
                int charBase = 0x06000000 + ((cnt >> 2) & 3) * 0x4000;
                int screenBase = 0x06000000 + ((cnt >> 8) & 0x1F) * 0x800;
                java.util.Set<Integer> tiles = new java.util.HashSet<>();
                int nonZeroEntries = 0;
                for (int e = 0; e < 32 * 32; e++) {
                    int m = console.bus().read16(screenBase + e * 2);
                    tiles.add(m & 0x3FF);
                    if ((m & 0x3FF) != 0) nonZeroEntries++;
                }
                int nonZeroTiles = 0;
                for (int t = 0; t < 512; t++) {
                    for (int w = 0; w < 8; w++) {
                        if (console.bus().read32(charBase + t * 32 + w * 4) != 0) {
                            nonZeroTiles++;
                            break;
                        }
                    }
                }
                System.out.printf("    BG%d tilemap: %d non-zero entries, %d distinct tiles | charblock: %d non-zero tiles%n",
                        bg, nonZeroEntries, tiles.size(), nonZeroTiles);
            }
            // Which palette banks does BG0's tilemap use, and are those banks green?
            int bg0cnt = console.bus().read16(0x04000008);
            int bg0Screen = 0x06000000 + ((bg0cnt >> 8) & 0x1F) * 0x800;
            java.util.Set<Integer> banks = new java.util.TreeSet<>();
            for (int e = 0; e < 32 * 32; e++) {
                banks.add((console.bus().read16(bg0Screen + e * 2) >> 12) & 0xF);
            }
            System.out.println("    BG0 palette banks used: " + banks);
            for (int bank : banks) {
                StringBuilder pal = new StringBuilder();
                for (int c = 0; c < 8; c++) {
                    pal.append(String.format(" %04X", console.bus().read16(0x05000000 + (bank * 16 + c) * 2)));
                }
                System.out.printf("    BGpal bank%d:%s%n", bank, pal);
            }
        }
    }

    private static void isolate(GbaConsole console, int savedDispcnt, int dispcnt, String fileName) {
        console.bus().write16(DISPCNT, dispcnt);
        writePng(console.renderFrame(), fileName);
        console.bus().write16(DISPCNT, savedDispcnt);
    }

    private static void writePng(int[] frame, String fileName) {
        try {
            Path out = Path.of("target", fileName);
            Files.createDirectories(out.getParent());
            ImageIO.write(GbaFrameImage.fromArgb(frame, GbaVideo.WIDTH, GbaVideo.HEIGHT), "png", out.toFile());
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
