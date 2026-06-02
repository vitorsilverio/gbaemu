package dev.vitorsilverio.gbaemu.bios;

import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import dev.vitorsilverio.gbaemu.system.GbaSystemControl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaBiosSwiTest {
    @Test
    void registerRamResetClearsSelectedMemoryRegions() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, 0x12345678);
        memory.write32(0x03000000, 0x87654321);
        memory.write32(0x05000000, 0x11111111);

        dispatcher.dispatch(0x01, state(0x03));

        assertEquals(0, memory.read32(0x02000000));
        assertEquals(0, memory.read32(0x03000000));
        assertEquals(0x11111111, memory.read32(0x05000000));
    }

    @Test
    void haltAndStopUpdateSystemControl() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaSystemControl system = new GbaSystemControl(memory);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, system);

        dispatcher.dispatch(0x02, state(0));
        assertTrue(system.halted());

        dispatcher.dispatch(0x03, state(0));
        assertTrue(system.stopped());
    }

    @Test
    void divReturnsQuotientRemainderAndAbsQuotient() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState result = dispatcher.dispatch(0x06, new CpuState(-7, 3, 0, 0, 0, 0, 0, 0));

        assertEquals(-2, result.r0());
        assertEquals(-1, result.r1());
        assertEquals(2, result.r3());
    }

    @Test
    void sqrtReturnsIntegerSquareRoot() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState result = dispatcher.dispatch(0x08, state(81));

        assertEquals(9, result.r0());
    }

    @Test
    void arcTan2ReturnsBiosAngleUnits() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState right = dispatcher.dispatch(0x0A, new CpuState(1, 0, 0, 0, 0, 0, 0, 0));
        CpuState up = dispatcher.dispatch(0x0A, new CpuState(0, 1, 0, 0, 0, 0, 0, 0));
        CpuState left = dispatcher.dispatch(0x0A, new CpuState(-1, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(0x0000, right.r0());
        assertEquals(0x4000, up.r0());
        assertEquals(0x8000, left.r0());
    }

    @Test
    void cpuSetCopiesHalfwordsAndSupportsFixedSourceFill() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write16(0x02000000, 0x1234);
        memory.write16(0x02000002, 0x5678);

        dispatcher.dispatch(0x0B, new CpuState(0x02000000, 0x03000000, 2, 0, 0, 0, 0, 0));
        dispatcher.dispatch(0x0B, new CpuState(0x02000000, 0x03000010, (1 << 24) | 2, 0, 0, 0, 0, 0));

        assertEquals(0x1234, memory.read16(0x03000000));
        assertEquals(0x5678, memory.read16(0x03000002));
        assertEquals(0x1234, memory.read16(0x03000010));
        assertEquals(0x1234, memory.read16(0x03000012));
    }

    @Test
    void cpuFastSetCopiesRoundedUpWordCount() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        for (int i = 0; i < 16; i++) {
            memory.write32(0x02000000 + i * 4, 0x1000 + i);
        }
        memory.write32(0x03000040, 0xDEADBEEF);

        dispatcher.dispatch(0x0C, new CpuState(0x02000000, 0x03000000, 9, 0, 0, 0, 0, 0));

        assertEquals(0x1000, memory.read32(0x03000000));
        assertEquals(0x100F, memory.read32(0x0300003C));
        assertEquals(0xDEADBEEF, memory.read32(0x03000040));
    }

    @Test
    void bgAffineSetWritesIdentityTransform() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write16(0x0200000C, 0x0100);
        memory.write16(0x0200000E, 0x0100);

        dispatcher.dispatch(0x0E, new CpuState(0x02000000, 0x03000000, 1, 0, 0, 0, 0, 0));

        assertEquals(0x0100, memory.read16(0x03000000));
        assertEquals(0, memory.read16(0x03000002));
        assertEquals(0, memory.read16(0x03000004));
        assertEquals(0x0100, memory.read16(0x03000006));
        assertEquals(0, memory.read32(0x03000008));
        assertEquals(0, memory.read32(0x0300000C));
    }

    @Test
    void objAffineSetWritesMatrixUsingOffset() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write16(0x02000000, 0x0100);
        memory.write16(0x02000002, 0x0100);

        dispatcher.dispatch(0x0F, new CpuState(0x02000000, 0x03000000, 1, 8, 0, 0, 0, 0));

        assertEquals(0x0100, memory.read16(0x03000000));
        assertEquals(0, memory.read16(0x03000010));
        assertEquals(0, memory.read16(0x03000020));
        assertEquals(0x0100, memory.read16(0x03000030));
    }

    @Test
    void bitUnpackExpandsPackedNibblesIntoBytes() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write8(0x02000000, 0x21);
        memory.write16(0x02000010, 1);
        memory.write8(0x02000012, 4);
        memory.write8(0x02000013, 8);

        dispatcher.dispatch(0x10, new CpuState(0x02000000, 0x03000000, 0x02000010, 0, 0, 0, 0, 0));

        assertEquals(0x01, memory.read8(0x03000000));
        assertEquals(0x02, memory.read8(0x03000001));
    }

    @Test
    void lz77UncompressesRawAndCompressedBlocksToWram() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (6 << 8) | 0x10);
        memory.write8(0x02000004, 0x10);
        memory.write8(0x02000005, 'A');
        memory.write8(0x02000006, 'B');
        memory.write8(0x02000007, 'C');
        memory.write8(0x02000008, 0x10);
        memory.write8(0x02000009, 0x02);

        dispatcher.dispatch(0x11, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals('A', memory.read8(0x03000000));
        assertEquals('B', memory.read8(0x03000001));
        assertEquals('C', memory.read8(0x03000002));
        assertEquals('A', memory.read8(0x03000003));
        assertEquals('B', memory.read8(0x03000004));
        assertEquals('C', memory.read8(0x03000005));
    }

    @Test
    void lz77VramVariantWritesHalfwords() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (2 << 8) | 0x10);
        memory.write8(0x02000004, 0x00);
        memory.write8(0x02000005, 0x12);
        memory.write8(0x02000006, 0x34);

        dispatcher.dispatch(0x12, new CpuState(0x02000000, 0x06000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x3412, memory.read16(0x06000000));
    }

    @Test
    void huffmanUncompressesEightBitSymbols() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (4 << 8) | 0x20 | 8);
        memory.write8(0x02000004, 1);
        memory.write8(0x02000005, 0xC0);
        memory.write8(0x02000006, 'A');
        memory.write8(0x02000007, 'B');
        memory.write32(0x02000008, 0x50000000);

        dispatcher.dispatch(0x13, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals('A', memory.read8(0x03000000));
        assertEquals('B', memory.read8(0x03000001));
        assertEquals('A', memory.read8(0x03000002));
        assertEquals('B', memory.read8(0x03000003));
    }

    @Test
    void rlUncompressesLiteralAndRepeatedBlocks() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (5 << 8) | 0x30);
        memory.write8(0x02000004, 0x01);
        memory.write8(0x02000005, 0x12);
        memory.write8(0x02000006, 0x34);
        memory.write8(0x02000007, 0x80);
        memory.write8(0x02000008, 0x56);

        dispatcher.dispatch(0x14, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x12, memory.read8(0x03000000));
        assertEquals(0x34, memory.read8(0x03000001));
        assertEquals(0x56, memory.read8(0x03000002));
        assertEquals(0x56, memory.read8(0x03000003));
        assertEquals(0x56, memory.read8(0x03000004));
    }

    @Test
    void softResetReturnsToRomOrMultibootEntryPoint() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState rom = dispatcher.dispatch(0x00, new CpuState(1, 2, 3, 4, 5, 6, 7, 0x3F));
        memory.write8(0x03007FFA, 1);
        CpuState multiboot = dispatcher.dispatch(0x00, new CpuState(1, 2, 3, 4, 5, 6, 7, 0x3F));

        assertEquals(0x08000000, rom.pc());
        assertEquals(0x02000000, multiboot.pc());
        assertEquals(0x03007F00, rom.sp());
        assertEquals(0, rom.r0());
    }

    @Test
    void getBiosChecksumReturnsKnownValue() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState result = dispatcher.dispatch(0x0D, state(0));

        assertEquals(0xBAAE187F, result.r0());
    }

    @Test
    void diff8UnfilterReconstructsCumulativeBytes() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (4 << 8) | 0x80);
        memory.write8(0x02000004, 0x10);
        memory.write8(0x02000005, 0x02);
        memory.write8(0x02000006, 0xFE);
        memory.write8(0x02000007, 0x01);

        dispatcher.dispatch(0x16, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x10, memory.read8(0x03000000));
        assertEquals(0x12, memory.read8(0x03000001));
        assertEquals(0x10, memory.read8(0x03000002));
        assertEquals(0x11, memory.read8(0x03000003));
    }

    @Test
    void diff16UnfilterReconstructsCumulativeHalfwords() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));
        memory.write32(0x02000000, (4 << 8) | 0x80);
        memory.write16(0x02000004, 0x1000);
        memory.write16(0x02000006, 0x0002);

        dispatcher.dispatch(0x18, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x1000, memory.read16(0x03000000));
        assertEquals(0x1002, memory.read16(0x03000002));
    }

    @Test
    void allDocumentedSwiNumbersAreRegistered() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        for (int swi = 0; swi <= 0x2A; swi++) {
            int number = swi;
            assertDoesNotThrow(() -> dispatcher.dispatch(number, state(0)));
        }
    }

    @Test
    void signExtendedThumbSwiNumbersUseLowByte() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState result = dispatcher.dispatch(0xFFFF04, state(123));

        assertEquals(0, result.r0());
    }

    @Test
    void undocumentedSignExtendedThumbSwiIsNoOp() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        CpuState result = dispatcher.dispatch(0xFFFFFF, state(123));

        assertEquals(123, result.r0());
    }

    @Test
    void unknownSwiThrowsUsefulError() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(memory, new GbaSystemControl(memory));

        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> dispatcher.dispatch(0x2B, state(0)));
        assertTrue(exception.getMessage().contains("0x2b"));
    }

    private static CpuState state(int r0) {
        return new CpuState(r0, 0, 0, 0, 0, 0, 0, 0);
    }
}
