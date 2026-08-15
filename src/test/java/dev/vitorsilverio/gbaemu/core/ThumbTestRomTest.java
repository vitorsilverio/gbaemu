package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Integration regression test that runs the jsmolka THUMB CPU test ROM end to end
/// through the JIT block path (the path the desktop window uses) and verifies that
/// every instruction test passes.
///
/// This guards subtle ARM7TDMI (ARMv4T) behaviours that unit tests missed:
///  - POP/LDM/LDR into PC must not interwork (only BX switches state), and
///  - a THUMB block store of PC writes `addr + 6` (the empty register-list quirk).
class ThumbTestRomTest {

    @Test
    void thumbTestRomReportsAllTestsPassed() throws Exception {
        Path romPath = Path.of("gba-tests/thumb/thumb.gba");
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

        // r7 holds the failing test number; 0 means every THUMB test passed.
        assertEquals(0, cpu.register(7),
                "thumb.gba reported failing test number " + cpu.register(7) + " in r7");
    }
}
