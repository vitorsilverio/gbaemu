package dev.vitorsilverio.gbaemu.cartridge;

import dev.vitorsilverio.gbaemu.memory.GbaBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaSaveMemoryTest {
    @Test
    void startsErasedToAllOnes() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.SRAM);

        assertEquals(0xFF, save.read8(0));
    }

    @Test
    void mirrorsByBackingSize() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.SRAM);

        save.write8(1, 0x42);

        assertEquals(0x42, save.read8(GbaSaveMemory.SRAM_SIZE + 1));
    }

    @Test
    void noneSaveIgnoresWritesAndReadsErased() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.NONE);

        save.write8(0, 0);

        assertEquals(0xFF, save.read8(0));
        assertEquals(0, save.size());
    }

    @Test
    void snapshotAndLoadRoundTrip() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.SRAM);
        save.write8(0, 0x12);
        save.write8(1, 0x34);

        GbaSaveMemory loaded = GbaSaveMemory.forType(GbaSaveType.SRAM);
        loaded.load(save.snapshot());

        assertArrayEquals(save.snapshot(), loaded.snapshot());
    }

    @Test
    void busUsesSaveMemoryForSramRegion() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.SRAM);
        GbaBus bus = new GbaBus();
        bus.add(save);

        bus.write8(0x0E000000, 0x5A);

        assertEquals(0x5A, save.read8(0));
        assertEquals(0x5A, bus.read8(0x0E010000));
    }

    @Test
    void flashEntersAndExitsIdModeWithUnlockSequence() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);

        writeFlashCommand(save, 0x90);

        assertEquals(0xC2, save.read8(0));
        assertEquals(0x09, save.read8(1));

        save.write8(0, 0xF0);

        assertEquals(0xFF, save.read8(0));
    }

    @Test
    void flashProgramOnlyClearsBitsAfterUnlockSequence() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_512);

        writeFlashCommand(save, 0xA0);
        save.write8(0x1234, 0x5A);
        writeFlashCommand(save, 0xA0);
        save.write8(0x1234, 0x3C);

        assertEquals(0x18, save.read8(0x1234));
    }

    @Test
    void flashProgramsDataByteEqualToResetCommand() {
        // 0xF0 is the flash reset command, but as a PROGRAM data byte it must be written verbatim.
        // Swallowing it would leave 0xFF and fail the game's write-verify (e.g. Mario Kart saves).
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH);

        writeFlashCommand(save, 0xA0);
        save.write8(0x1234, 0xF0);

        assertEquals(0xF0, save.read8(0x1234));

        // And a subsequent unlock sequence still works (write state was properly reset).
        writeFlashCommand(save, 0xA0);
        save.write8(0x1235, 0x0F);
        assertEquals(0x0F, save.read8(0x1235));
    }

    @Test
    void flashChipEraseRestoresProgrammedBytes() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_512);
        writeFlashCommand(save, 0xA0);
        save.write8(0x1234, 0x00);

        writeFlashCommand(save, 0x80);
        writeFlashCommand(save, 0x10);

        assertEquals(0xFF, save.read8(0x1234));
    }

    @Test
    void flashOneMegabitUsesSelectedBank() {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);
        writeFlashCommand(save, 0xA0);
        save.write8(0x0042, 0x11);

        writeFlashCommand(save, 0xB0);
        save.write8(0, 1);
        writeFlashCommand(save, 0xA0);
        save.write8(0x0042, 0x22);

        assertEquals(0x22, save.read8(0x0042));

        writeFlashCommand(save, 0xB0);
        save.write8(0, 0);

        assertEquals(0x11, save.read8(0x0042));
    }

    private static void writeFlashCommand(GbaSaveMemory save, int command) {
        save.write8(0x5555, 0xAA);
        save.write8(0x2AAA, 0x55);
        save.write8(0x5555, command);
    }
}
