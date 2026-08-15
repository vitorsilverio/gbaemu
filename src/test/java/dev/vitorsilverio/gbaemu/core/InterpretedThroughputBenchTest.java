package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Local-only headless bench (task C8, fase 1): measures wall-clock throughput of the
/// INTERPRETED backend for the 5 reference games, skip-BIOS boot. This is the production path
/// of the gbaemu — {@link GbaConsole#fromRom(byte[])} defaults to interpreted; this bench forces
/// it explicitly via {@code fromRom(rom, false)}. Prints a table; does not assert on speed
/// (machine-timing noise) — only that every run makes progress. This is the ANTES/DEPOIS
/// measurement for the whole C8 task — re-run after each fase-2 candidate PR. Run explicitly
/// with: mvn -o -Dtest=InterpretedThroughputBenchTest test
class InterpretedThroughputBenchTest {
    private static final long CYCLE_BUDGET = 50_000_000L; // ~3s de GBA (16.78MHz)
    private static final List<String> GAMES = List.of(
            "roms/pokefirered.gba",
            "roms/smw.gba",
            "roms/castlevania.gba",
            "roms/metroid.gba",
            "roms/mariokart.gba");

    @Test
    void interpretedThroughputOnTheReferenceGames() throws Exception {
        assumeTrue(Files.exists(Path.of(GAMES.get(0))), "local ROMs (roms/) not present");
        System.err.printf("%-16s %12s%n", "game", "interp (ms)");
        for (String path : GAMES) {
            byte[] rom = Files.readAllBytes(Path.of(path));

            GbaConsole console = GbaConsole.fromRom(rom, false);
            long ms = timeRun(console);

            System.err.printf("%-16s %12d%n", path, ms);

            assertTrue(console.cpu().cycles() > 0, path + ": interpreted run made no progress");
        }
    }

    private static long timeRun(GbaConsole console) {
        long start = System.nanoTime();
        console.runCycles(CYCLE_BUDGET);
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
