package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Local-only regression guard (skips unless the BIOS/ROM files are present — the real BIOS is
/// not in the repo): with the real BIOS, the boot ROM must hand off to the cartridge. This guards
/// the "single-player + real BIOS = black screen" regression, whose root cause was a normal-mode
/// SIO slave transfer completing instantly + raising a SERIAL IRQ: the BIOS probes for a multiboot
/// host with repeated slave transfers, and the instant-IRQ storm starved its detection timeout so
/// it never booted the cart. Run explicitly with e.g.
///   mvn -o -Dtest=RealBiosBootDiagnosticTest -Dgba.rom=roms/metroid.gba test
class RealBiosBootDiagnosticTest {

    private static final int FRAME =
            dev.vitorsilverio.gbaemu.video.GbaLcdTiming.CYCLES_PER_SCANLINE
                    * dev.vitorsilverio.gbaemu.video.GbaLcdTiming.TOTAL_SCANLINES;

    private static byte[] readOrSkip(String property, String fallback) throws Exception {
        Path path = Path.of(System.getProperty(property, fallback));
        Assumptions.assumeTrue(Files.exists(path), "missing " + path + " (set -D" + property + ")");
        return Files.readAllBytes(path);
    }

    /// Runs up to {@code maxFrames} and returns the frame at which the PC first enters the ROM
    /// region (0x08000000+), or -1 if it never does (i.e. the BIOS never booted the cartridge).
    private static int framesToBootRom(GbaConsole console, int maxFrames) {
        for (int f = 0; f < maxFrames; f++) {
            console.runCycles(FRAME);
            int pc = console.cpu().programCounter();
            if (pc >= 0x08000000 && pc < 0x0E000000) {
                return f;
            }
        }
        return -1;
    }

    @Test
    void realBiosBootsTheCartridge() throws Exception {
        byte[] bios = readOrSkip("gba.bios", "gba_bios.bin");
        byte[] rom = readOrSkip("gba.rom", "roms/pokefirered.gba");
        int booted = framesToBootRom(GbaConsole.fromBiosAndRom(bios, rom), 1200);
        System.err.println("[REAL] booted cart at frame " + booted);
        assertTrue(booted >= 0,
                "real BIOS must hand off to the cartridge (regression: a normal-mode SIO IRQ storm hung boot)");
    }

    @Test
    void hleBootsTheCartridge() throws Exception {
        byte[] rom = readOrSkip("gba.rom", "roms/pokefirered.gba");
        int booted = framesToBootRom(GbaConsole.fromRom(rom), 300);
        System.err.println("[HLE] booted cart at frame " + booted);
        assertTrue(booted >= 0);
    }
}
