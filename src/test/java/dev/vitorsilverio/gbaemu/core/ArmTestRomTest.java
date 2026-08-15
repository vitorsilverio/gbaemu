package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Integration regression test that runs the jsmolka ARM CPU test ROM end to end
/// through the JIT block path and verifies that every instruction test passes.
///
/// Guards subtle ARM7TDMI (ARMv4T) behaviours that share the single execution engine
/// with the THUMB tests: the shifter carry-out is read before the destination write,
/// R15 reads as PC+12 with a register-specified shift, the deprecated CMP/CMN/TST/TEQ
/// "P" form copies SPSR into CPSR, STR of R15 stores PC+12, a load whose base equals
/// its destination keeps the loaded value, and block transfers force word alignment.
class ArmTestRomTest {

    @Test
    void armTestRomReportsAllTestsPassed() throws Exception {
        Path romPath = Path.of("gba-tests/arm/arm.gba");
        assumeTrue(Files.exists(romPath), "gba-tests submodule not present");
        byte[] rom = Files.readAllBytes(romPath);
        GbaConsole console = GbaConsole.fromRom(rom);
        ArmCore cpu = console.cpu();

        // Enough blocks to run every test and spin through the trailing vblank waits.
        console.runBlocks(400_000);

        // The harness settles into `idle: b idle` (ARM) once all tests have run.
        int pc = cpu.programCounter();
        console.runBlocks(16);
        assertEquals(pc, cpu.programCounter(),
                "CPU should have settled in the idle loop, not still be running or derailed");
        assertTrue(Integer.compareUnsigned(pc, 0x08000000) >= 0
                        && Integer.compareUnsigned(pc, 0x0A000000) < 0,
                "PC escaped ROM (derailed); was 0x" + Integer.toHexString(pc));

        // r12 holds the failing test number; 0 means every ARM test passed.
        assertEquals(0, cpu.register(12),
                "arm.gba reported failing test number " + cpu.register(12) + " in r12");
    }
}
