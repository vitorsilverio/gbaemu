package dev.vitorsilverio.gbaemu.core;

import java.nio.file.Files;
import java.nio.file.Path;

/// Throwaway JFR-profiling harness for task C8 fase 1 (not a test, not shipped API): runs a
/// single reference game on the INTERPRETED backend for a fixed wall-clock budget so a JFR
/// recording can capture a representative hot-method profile of the production path. Run with:
///   java -XX:StartFlightRecording=duration=120s,filename=out.jfr,settings=profile,dumponexit=true \
///        -cp <test-classes>;<classes>;<deps> dev.vitorsilverio.gbaemu.core.InterpretedProfileMain roms/pokefirered.gba
public final class InterpretedProfileMain {
    private static final long CYCLE_CHUNK = 50_000_000L;

    private InterpretedProfileMain() {
    }

    public static void main(String[] args) throws Exception {
        String romPath = args.length > 0 ? args[0] : "roms/pokefirered.gba";
        long wallBudgetMs = args.length > 1 ? Long.parseLong(args[1]) : 120_000L;

        byte[] rom = Files.readAllBytes(Path.of(romPath));
        GbaConsole console = GbaConsole.fromRom(rom, false);

        long start = System.currentTimeMillis();
        long chunks = 0;
        while (System.currentTimeMillis() - start < wallBudgetMs) {
            console.runCycles(CYCLE_CHUNK);
            chunks++;
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("chunks=%d cycles=%d elapsedMs=%d cpuCycles=%d%n",
                chunks, chunks * CYCLE_CHUNK, elapsed, console.cpu().cycles());
    }
}
