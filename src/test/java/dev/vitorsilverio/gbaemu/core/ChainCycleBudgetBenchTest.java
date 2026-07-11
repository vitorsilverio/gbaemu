package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Local-only headless bench (task C5): measures wall-clock throughput of the JIT backend with
/// block chaining OFF (baseline) vs ON at {@link GbaConsole#CHAIN_CYCLE_BUDGET} for the 5
/// reference games, skip-BIOS boot. Prints a table; does not assert on speed (machine-timing
/// noise) — only that both configurations make progress. Run explicitly with:
///   mvn -o -Dtest=ChainCycleBudgetBenchTest test
///
/// This covers the "headless bench antes/depois" leg of the C5 validation protocol. The
/// remaining legs (boot frio + ~1min de gameplay/audio/raster dos 5 jogos na GUI, ROMs de
/// teste gba-tests) precisam do usuario / ja rodam na suite padrao (ver GbaTestRomSuiteTest,
/// ArmTestRomTest, ThumbTestRomTest).
class ChainCycleBudgetBenchTest {
    private static final long CYCLE_BUDGET = 50_000_000L; // ~3s de GBA (16.78MHz)
    private static final List<String> GAMES = List.of(
            "roms/pokefirered.gba",
            "roms/smw.gba",
            "roms/castlevania.gba",
            "roms/metroid.gba",
            "roms/mariokart.gba");

    @Test
    void chainingDoesNotRegressThroughputOnTheReferenceGames() throws Exception {
        System.err.printf("%-16s %12s %12s %8s%n", "game", "off (ms)", "budget=32 (ms)", "delta");
        for (String path : GAMES) {
            byte[] rom = Files.readAllBytes(Path.of(path));

            GbaConsole off = GbaConsole.fromRom(rom, true);
            off.runtime().setChainCycleBudget(0);
            long offMs = timeRun(off);

            GbaConsole on = GbaConsole.fromRom(rom, true);
            on.runtime().setChainCycleBudget(GbaConsole.CHAIN_CYCLE_BUDGET);
            long onMs = timeRun(on);

            double deltaPct = offMs == 0 ? 0 : 100.0 * (offMs - onMs) / offMs;
            System.err.printf("%-16s %12d %12d %+7.1f%%%n", path, offMs, onMs, deltaPct);

            assertTrue(off.cpu().cycles() > 0, path + ": baseline made no progress");
            assertTrue(on.cpu().cycles() > 0, path + ": chained run made no progress");
        }
    }

    private static long timeRun(GbaConsole console) {
        long start = System.nanoTime();
        console.runCycles(CYCLE_BUDGET);
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
