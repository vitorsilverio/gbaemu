package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.gbaemu.cartridge.GbaSaveMemory;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveType;
import dev.vitorsilverio.gbaemu.cartridge.GbaRom;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.video.GbaVideoMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaMemoryTest {

    private static GbaBus createBus() {
        return createBus(new byte[0]);
    }

    private static GbaBus createBus(byte[] romBytes) {
        GbaBus bus = new GbaBus();
        bus.add(new GbaInterruptController());
        bus.add(new GbaEwram());
        bus.add(new GbaIwram());
        bus.add(new GbaVideoMemory(() -> 0));
        bus.add(new GbaRom(romBytes));
        bus.add(GbaSaveMemory.forType(GbaSaveType.SRAM));
        bus.add(new GbaBios(skipBiosStub()));
        return bus;
    }

    private static GbaBus createBusWithInterrupts(GbaInterruptController interrupts) {
        GbaBus bus = new GbaBus();
        bus.add(interrupts);
        bus.add(new GbaEwram());
        bus.add(new GbaIwram());
        return bus;
    }

    @Test
    void ewramIsMirroredThroughTheRegionWindow() {
        GbaBus bus = createBus();

        bus.write32(0x02000000, 0x12345678);

        assertEquals(0x12345678, bus.read32(0x02040000));
    }

    @Test
    void iwramIsLittleEndianAndMirrored() {
        GbaBus bus = createBus();

        bus.write32(0x03007FFC, 0xDEADBEEF);

        assertEquals(0xEF, bus.read8(0x03FFFFFC));
        assertEquals(0xBEEF, bus.read16(0x03FFFFFC));
        assertEquals(0xDEADBEEF, bus.read32(0x03FFFFFC));
    }

    @Test
    void halfwordAccessesAreAlignedLikeTheGbaBus() {
        GbaBus bus = createBus();

        bus.write16(0x02000001, 0xA55A);

        assertEquals(0x5A, bus.read8(0x02000000));
        assertEquals(0xA5, bus.read8(0x02000001));
        assertEquals(0xA55A, bus.read16(0x02000001));
    }

    @Test
    void unalignedWordReadsRotateTheAlignedWord() {
        GbaBus bus = createBus();

        bus.write32(0x02000000, 0x11223344);

        assertEquals(0x11223344, bus.read32(0x02000000));
        assertEquals(0x44112233, bus.read32(0x02000001));
        assertEquals(0x33441122, bus.read32(0x02000002));
        assertEquals(0x22334411, bus.read32(0x02000003));
    }

    @Test
    void wordWritesAreAlignedLikeTheGbaBus() {
        GbaBus bus = createBus();

        bus.write32(0x02000002, 0xCAFEBABE);

        assertEquals(0xCAFEBABE, bus.read32(0x02000000));
        assertEquals(0, bus.read32(0x02000004));
    }

    @Test
    void romIsReadOnlyAndVisibleInEachGamePakWaitStateWindow() {
        GbaBus bus = createBus(new byte[]{0x11, 0x22, 0x33, 0x44});

        bus.write32(0x08000000, 0xFFFFFFFF);

        assertEquals(0x44332211, bus.read32(0x08000000));
        assertEquals(0x44332211, bus.read32(0x0A000000));
        assertEquals(0x44332211, bus.read32(0x0C000000));
    }

    @Test
    void skipBiosInstallsIrqVectorStub() {
        GbaBus bus = createBus();

        assertEquals(0xE92D500F, bus.read32(0x00000018));
        assertEquals(0x03007FFC, bus.read32(0x00000040));
    }

    @Test
    void sramDefaultsToErasedFlashStateAndMirrorsEvery64KiB() {
        GbaBus bus = createBus();

        assertEquals(0xFF, bus.read8(0x0E000000));

        bus.write8(0x0E000001, 0x42);

        assertEquals(0x42, bus.read8(0x0E010001));
    }

    @Test
    void byteWritesToPaletteDuplicateTheByteAcrossTheHalfword() {
        GbaBus bus = createBus();

        bus.write8(0x05000001, 0x7B);

        assertEquals(0x7B7B, bus.read16(0x05000000));
    }

    @Test
    void byteWritesToObjVramAndOamAreIgnored() {
        GbaBus bus = createBus();

        bus.write8(0x06010000, 0x99);
        bus.write8(0x07000000, 0x88);

        assertEquals(0, bus.read8(0x06010000));
        assertEquals(0, bus.read8(0x07000000));
    }

    @Test
    void vramMirrorsTheLast32KiBOfThe128KiBWindowToObjVram() {
        GbaBus bus = createBus();

        bus.write16(0x06010000, 0x1234);

        assertEquals(0x1234, bus.read16(0x06018000));
    }

    @Test
    void unusedMemoryReturnsTheConfiguredOpenBusValue() {
        GbaBus bus = createBus();
        bus.setOpenBusValue(0xAABBCCDD);

        assertEquals(0xDD, bus.read8(0x01000000));
        assertEquals(0xCCDD, bus.read16(0x01000000));
        assertEquals(0xAABBCCDD, bus.read32(0x01000000));
    }

    @Test
    void interruptFlagRegisterClearsBitsByWritingOne() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaBus bus = createBusWithInterrupts(interrupts);

        interrupts.request(0b111);
        bus.write16(0x04000202, 0b101);

        assertEquals(0b010, bus.read16(0x04000202));
    }

    @Test
    void byteWritesToInterruptFlagRegisterClearOnlyTargetedByteBits() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaBus bus = createBusWithInterrupts(interrupts);

        interrupts.request(0x0303);
        bus.write8(0x04000202, 0x01);
        bus.write8(0x04000203, 0x02);

        assertEquals(0x0102, bus.read16(0x04000202));
    }

    private static byte[] skipBiosStub() {
        byte[] stub = new byte[GbaBios.SIZE];
        write32(stub, 0x18, 0xE92D500F);
        write32(stub, 0x1C, 0xE59F001C);
        write32(stub, 0x20, 0xE5900000);
        write32(stub, 0x24, 0xE3500000);
        write32(stub, 0x28, 0x0A000001);
        write32(stub, 0x2C, 0xE1A0E00F);
        write32(stub, 0x30, 0xE12FFF10);
        write32(stub, 0x34, 0xE8BD500F);
        write32(stub, 0x38, 0xE25EF004);
        write32(stub, 0x40, 0x03007FFC);
        return stub;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset]     = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
