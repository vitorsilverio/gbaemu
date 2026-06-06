package dev.vitorsilverio.gbaemu.bios;

import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.memory.GbaBus;
import dev.vitorsilverio.gbaemu.memory.GbaEwram;
import dev.vitorsilverio.gbaemu.memory.GbaIwram;
import dev.vitorsilverio.gbaemu.system.GbaSystemControl;
import dev.vitorsilverio.gbaemu.video.GbaVideoMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaBiosSwiTest {

    private static GbaBus createBus() {
        GbaBus bus = new GbaBus();
        bus.add(new GbaEwram());
        bus.add(new GbaIwram());
        bus.add(new GbaVideoMemory(() -> 0));
        return bus;
    }

    @Test
    void registerRamResetClearsSelectedMemoryRegions() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, 0x12345678);
        bus.write32(0x03000000, 0x87654321);
        bus.write32(0x05000000, 0x11111111);

        dispatcher.dispatch(0x01, state(0x03));

        assertEquals(0, bus.read32(0x02000000));
        assertEquals(0, bus.read32(0x03000000));
        assertEquals(0x11111111, bus.read32(0x05000000));
    }

    @Test
    void haltAndStopUpdateSystemControl() {
        GbaSystemControl system = new GbaSystemControl();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), system);

        dispatcher.dispatch(0x02, state(0));
        assertTrue(system.halted());

        dispatcher.dispatch(0x03, state(0));
        assertTrue(system.stopped());
    }

    @Test
    void divReturnsQuotientRemainderAndAbsQuotient() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState result = dispatcher.dispatch(0x06, new CpuState(-7, 3, 0, 0, 0, 0, 0, 0));

        assertEquals(-2, result.r0());
        assertEquals(-1, result.r1());
        assertEquals(2, result.r3());
    }

    @Test
    void sqrtReturnsIntegerSquareRoot() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState result = dispatcher.dispatch(0x08, state(81));

        assertEquals(9, result.r0());
    }

    @Test
    void arcTan2ReturnsBiosAngleUnits() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState right = dispatcher.dispatch(0x0A, new CpuState(1, 0, 0, 0, 0, 0, 0, 0));
        CpuState up = dispatcher.dispatch(0x0A, new CpuState(0, 1, 0, 0, 0, 0, 0, 0));
        CpuState left = dispatcher.dispatch(0x0A, new CpuState(-1, 0, 0, 0, 0, 0, 0, 0));

        assertEquals(0x0000, right.r0());
        assertEquals(0x4000, up.r0());
        assertEquals(0x8000, left.r0());
    }

    @Test
    void cpuSetCopiesHalfwordsAndSupportsFixedSourceFill() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write16(0x02000000, 0x1234);
        bus.write16(0x02000002, 0x5678);

        dispatcher.dispatch(0x0B, new CpuState(0x02000000, 0x03000000, 2, 0, 0, 0, 0, 0));
        dispatcher.dispatch(0x0B, new CpuState(0x02000000, 0x03000010, (1 << 24) | 2, 0, 0, 0, 0, 0));

        assertEquals(0x1234, bus.read16(0x03000000));
        assertEquals(0x5678, bus.read16(0x03000002));
        assertEquals(0x1234, bus.read16(0x03000010));
        assertEquals(0x1234, bus.read16(0x03000012));
    }

    @Test
    void cpuFastSetCopiesRoundedUpWordCount() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        for (int i = 0; i < 16; i++) {
            bus.write32(0x02000000 + i * 4, 0x1000 + i);
        }
        bus.write32(0x03000040, 0xDEADBEEF);

        dispatcher.dispatch(0x0C, new CpuState(0x02000000, 0x03000000, 9, 0, 0, 0, 0, 0));

        assertEquals(0x1000, bus.read32(0x03000000));
        assertEquals(0x100F, bus.read32(0x0300003C));
        assertEquals(0xDEADBEEF, bus.read32(0x03000040));
    }

    @Test
    void bgAffineSetWritesIdentityTransform() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write16(0x0200000C, 0x0100);
        bus.write16(0x0200000E, 0x0100);

        dispatcher.dispatch(0x0E, new CpuState(0x02000000, 0x03000000, 1, 0, 0, 0, 0, 0));

        assertEquals(0x0100, bus.read16(0x03000000));
        assertEquals(0, bus.read16(0x03000002));
        assertEquals(0, bus.read16(0x03000004));
        assertEquals(0x0100, bus.read16(0x03000006));
        assertEquals(0, bus.read32(0x03000008));
        assertEquals(0, bus.read32(0x0300000C));
    }

    @Test
    void objAffineSetWritesMatrixUsingOffset() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write16(0x02000000, 0x0100);
        bus.write16(0x02000002, 0x0100);

        dispatcher.dispatch(0x0F, new CpuState(0x02000000, 0x03000000, 1, 8, 0, 0, 0, 0));

        assertEquals(0x0100, bus.read16(0x03000000));
        assertEquals(0, bus.read16(0x03000010));
        assertEquals(0, bus.read16(0x03000020));
        assertEquals(0x0100, bus.read16(0x03000030));
    }

    @Test
    void bitUnpackExpandsPackedNibblesIntoBytes() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write8(0x02000000, 0x21);
        bus.write16(0x02000010, 1);
        bus.write8(0x02000012, 4);
        bus.write8(0x02000013, 8);

        dispatcher.dispatch(0x10, new CpuState(0x02000000, 0x03000000, 0x02000010, 0, 0, 0, 0, 0));

        assertEquals(0x01, bus.read8(0x03000000));
        assertEquals(0x02, bus.read8(0x03000001));
    }

    @Test
    void lz77UncompressesRawAndCompressedBlocksToWram() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (6 << 8) | 0x10);
        bus.write8(0x02000004, 0x10);
        bus.write8(0x02000005, 'A');
        bus.write8(0x02000006, 'B');
        bus.write8(0x02000007, 'C');
        bus.write8(0x02000008, 0x10);
        bus.write8(0x02000009, 0x02);

        dispatcher.dispatch(0x11, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals('A', bus.read8(0x03000000));
        assertEquals('B', bus.read8(0x03000001));
        assertEquals('C', bus.read8(0x03000002));
        assertEquals('A', bus.read8(0x03000003));
        assertEquals('B', bus.read8(0x03000004));
        assertEquals('C', bus.read8(0x03000005));
    }

    @Test
    void lz77VramVariantWritesHalfwords() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (2 << 8) | 0x10);
        bus.write8(0x02000004, 0x00);
        bus.write8(0x02000005, 0x12);
        bus.write8(0x02000006, 0x34);

        dispatcher.dispatch(0x12, new CpuState(0x02000000, 0x06000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x3412, bus.read16(0x06000000));
    }

    @Test
    void huffmanUncompressesEightBitSymbols() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (4 << 8) | 0x20 | 8);
        bus.write8(0x02000004, 1);
        bus.write8(0x02000005, 0xC0);
        bus.write8(0x02000006, 'A');
        bus.write8(0x02000007, 'B');
        bus.write32(0x02000008, 0x50000000);

        dispatcher.dispatch(0x13, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals('A', bus.read8(0x03000000));
        assertEquals('B', bus.read8(0x03000001));
        assertEquals('A', bus.read8(0x03000002));
        assertEquals('B', bus.read8(0x03000003));
    }

    @Test
    void rlUncompressesLiteralAndRepeatedBlocks() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (5 << 8) | 0x30);
        bus.write8(0x02000004, 0x01);
        bus.write8(0x02000005, 0x12);
        bus.write8(0x02000006, 0x34);
        bus.write8(0x02000007, 0x80);
        bus.write8(0x02000008, 0x56);

        dispatcher.dispatch(0x14, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x12, bus.read8(0x03000000));
        assertEquals(0x34, bus.read8(0x03000001));
        assertEquals(0x56, bus.read8(0x03000002));
        assertEquals(0x56, bus.read8(0x03000003));
        assertEquals(0x56, bus.read8(0x03000004));
    }

    @Test
    void softResetReturnsToRomOrMultibootEntryPoint() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());

        CpuState rom = dispatcher.dispatch(0x00, new CpuState(1, 2, 3, 4, 5, 6, 7, 0x3F));
        bus.write8(0x03007FFA, 1);
        CpuState multiboot = dispatcher.dispatch(0x00, new CpuState(1, 2, 3, 4, 5, 6, 7, 0x3F));

        assertEquals(0x08000000, rom.pc());
        assertEquals(0x02000000, multiboot.pc());
        assertEquals(0x03007F00, rom.sp());
        assertEquals(0, rom.r0());
    }

    @Test
    void getBiosChecksumReturnsKnownValue() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState result = dispatcher.dispatch(0x0D, state(0));

        assertEquals(0xBAAE187F, result.r0());
    }

    @Test
    void diff8UnfilterReconstructsCumulativeBytes() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (4 << 8) | 0x80);
        bus.write8(0x02000004, 0x10);
        bus.write8(0x02000005, 0x02);
        bus.write8(0x02000006, 0xFE);
        bus.write8(0x02000007, 0x01);

        dispatcher.dispatch(0x16, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x10, bus.read8(0x03000000));
        assertEquals(0x12, bus.read8(0x03000001));
        assertEquals(0x10, bus.read8(0x03000002));
        assertEquals(0x11, bus.read8(0x03000003));
    }

    @Test
    void diff16UnfilterReconstructsCumulativeHalfwords() {
        GbaBus bus = createBus();
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(bus, new GbaSystemControl());
        bus.write32(0x02000000, (4 << 8) | 0x80);
        bus.write16(0x02000004, 0x1000);
        bus.write16(0x02000006, 0x0002);

        dispatcher.dispatch(0x18, new CpuState(0x02000000, 0x03000000, 0, 0, 0, 0, 0, 0));

        assertEquals(0x1000, bus.read16(0x03000000));
        assertEquals(0x1002, bus.read16(0x03000002));
    }

    @Test
    void allDocumentedSwiNumbersAreRegistered() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        for (int swi = 0; swi <= 0x2A; swi++) {
            int number = swi;
            assertDoesNotThrow(() -> dispatcher.dispatch(number, state(0)));
        }
    }

    @Test
    void signExtendedThumbSwiNumbersUseLowByte() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState result = dispatcher.dispatch(0xFFFF04, state(123));

        assertEquals(0, result.r0());
    }

    @Test
    void undocumentedSignExtendedThumbSwiIsNoOp() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        CpuState result = dispatcher.dispatch(0xFFFFFF, state(123));

        assertEquals(123, result.r0());
    }

    @Test
    void unknownSwiThrowsUsefulError() {
        SwiDispatcher dispatcher = GbaBiosSwi.dispatcher(createBus(), new GbaSystemControl());

        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> dispatcher.dispatch(0x2B, state(0)));
        assertTrue(exception.getMessage().contains("0x2b"));
    }

    private static CpuState state(int r0) {
        return new CpuState(r0, 0, 0, 0, 0, 0, 0, 0);
    }
}
