package dev.vitorsilverio.gbaemu.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaMemoryTest {
    @Test
    void ewramIsMirroredThroughTheRegionWindow() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(0x02000000, 0x12345678);

        assertEquals(0x12345678, memory.read32(0x02040000));
    }

    @Test
    void iwramIsLittleEndianAndMirrored() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(0x03007FFC, 0xDEADBEEF);

        assertEquals(0xEF, memory.read8(0x03FFFFFC));
        assertEquals(0xBEEF, memory.read16(0x03FFFFFC));
        assertEquals(0xDEADBEEF, memory.read32(0x03FFFFFC));
    }

    @Test
    void halfwordAccessesAreAlignedLikeTheGbaBus() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(0x02000001, 0xA55A);

        assertEquals(0x5A, memory.read8(0x02000000));
        assertEquals(0xA5, memory.read8(0x02000001));
        assertEquals(0xA55A, memory.read16(0x02000001));
    }

    @Test
    void unalignedWordReadsRotateTheAlignedWord() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(0x02000000, 0x11223344);

        assertEquals(0x11223344, memory.read32(0x02000000));
        assertEquals(0x44112233, memory.read32(0x02000001));
        assertEquals(0x33441122, memory.read32(0x02000002));
        assertEquals(0x22334411, memory.read32(0x02000003));
    }

    @Test
    void wordWritesAreAlignedLikeTheGbaBus() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(0x02000002, 0xCAFEBABE);

        assertEquals(0xCAFEBABE, memory.read32(0x02000000));
        assertEquals(0, memory.read32(0x02000004));
    }

    @Test
    void romIsReadOnlyAndVisibleInEachGamePakWaitStateWindow() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[]{0x11, 0x22, 0x33, 0x44});

        memory.write32(0x08000000, 0xFFFFFFFF);

        assertEquals(0x44332211, memory.read32(0x08000000));
        assertEquals(0x44332211, memory.read32(0x0A000000));
        assertEquals(0x44332211, memory.read32(0x0C000000));
    }

    @Test
    void skipBiosInstallsIrqVectorStub() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        assertEquals(0xE92D500F, memory.read32(0x00000018));
        assertEquals(0x03007FFC, memory.read32(0x00000040));
    }

    @Test
    void sramDefaultsToErasedFlashStateAndMirrorsEvery64KiB() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        assertEquals(0xFF, memory.read8(0x0E000000));

        memory.write8(0x0E000001, 0x42);

        assertEquals(0x42, memory.read8(0x0E010001));
    }

    @Test
    void byteWritesToPaletteDuplicateTheByteAcrossTheHalfword() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write8(0x05000001, 0x7B);

        assertEquals(0x7B7B, memory.read16(0x05000000));
    }

    @Test
    void byteWritesToObjVramAndOamAreIgnored() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write8(0x06010000, 0x99);
        memory.write8(0x07000000, 0x88);

        assertEquals(0, memory.read8(0x06010000));
        assertEquals(0, memory.read8(0x07000000));
    }

    @Test
    void vramMirrorsTheLast32KiBOfThe128KiBWindowToObjVram() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(0x06010000, 0x1234);

        assertEquals(0x1234, memory.read16(0x06018000));
    }

    @Test
    void unusedMemoryReturnsTheConfiguredOpenBusValue() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        memory.setOpenBusValue(0xAABBCCDD);

        assertEquals(0xDD, memory.read8(0x01000000));
        assertEquals(0xCCDD, memory.read16(0x01000000));
        assertEquals(0xAABBCCDD, memory.read32(0x01000000));
    }

    @Test
    void interruptFlagRegisterClearsBitsByWritingOne() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.requestInterruptFlags(0b111);
        memory.write16(0x04000202, 0b101);

        assertEquals(0b010, memory.read16(0x04000202));
    }

    @Test
    void byteWritesToInterruptFlagRegisterClearOnlyTargetedByteBits() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.requestInterruptFlags(0x0303);
        memory.write8(0x04000202, 0x01);
        memory.write8(0x04000203, 0x02);

        assertEquals(0x0102, memory.read16(0x04000202));
    }
}
