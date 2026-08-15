package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Runs the remaining jsmolka gba-tests ROMs that follow the on-screen result-register
/// harness (result register 0 means every test passed, otherwise it holds the number of
/// the first failing test) end to end through the JIT block path.
///
/// arm.gba and thumb.gba have their own dedicated tests ([ArmTestRomTest],
/// [ThumbTestRomTest]); this suite covers the memory mirroring/STRB ROM and the save
/// chip ROMs (none/SRAM/FLASH64/FLASH128), all of which keep their result in r12. The
/// `unsafe.gba` ROM is deliberately excluded: its author documents that it fails on real
/// hardware (a flash-card artifact) and should not be part of a conformance suite.
class GbaTestRomSuiteTest {

    static Stream<Arguments> harnessRoms() {
        return Stream.of(
                Arguments.of("gba-tests/memory/memory.gba", 12),
                Arguments.of("gba-tests/save/none.gba", 12),
                Arguments.of("gba-tests/save/sram.gba", 12),
                Arguments.of("gba-tests/save/flash64.gba", 12),
                Arguments.of("gba-tests/save/flash128.gba", 12));
    }

    @ParameterizedTest(name = "{0} reports all tests passed")
    @MethodSource("harnessRoms")
    void romReportsAllTestsPassed(String romPath, int resultRegister) throws Exception {
        assumeTrue(Files.exists(Path.of(romPath)), "gba-tests submodule not present");
        byte[] rom = Files.readAllBytes(Path.of(romPath));
        GbaConsole console = GbaConsole.fromRom(rom);
        ArmCore cpu = console.cpu();

        // Enough blocks to run every test and spin through the trailing vblank waits.
        console.runBlocks(400_000);

        // The harness settles into `idle: b idle` once all tests have run.
        int pc = cpu.programCounter();
        console.runBlocks(16);
        assertEquals(pc, cpu.programCounter(),
                romPath + ": CPU should have settled in the idle loop, not still be running or derailed");
        assertTrue(Integer.compareUnsigned(pc, 0x08000000) >= 0
                        && Integer.compareUnsigned(pc, 0x0A000000) < 0,
                romPath + ": PC escaped ROM (derailed); was 0x" + Integer.toHexString(pc));

        assertEquals(0, cpu.register(resultRegister),
                romPath + " reported failing test number " + cpu.register(resultRegister));
    }
}
