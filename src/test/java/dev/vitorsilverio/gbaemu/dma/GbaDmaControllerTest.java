package dev.vitorsilverio.gbaemu.dma;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaDmaControllerTest {
    @Test
    void immediateHalfwordTransferCopiesMemoryAndDisablesChannel() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0x1234);
        memory.write16(0x02000002, 0x5678);
        setupDma(memory, 0, 0x02000000, 0x03000000, 2, 1 << 15);

        dma.triggerImmediateTransfers();

        assertEquals(0x1234, memory.read16(0x03000000));
        assertEquals(0x5678, memory.read16(0x03000002));
        assertEquals(0, memory.read16(0x040000BA) & (1 << 15));
    }

    @Test
    void immediateWordTransferCopiesToPaletteRam() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write32(0x02000000, 0x03E0001F);
        setupDma(memory, 3, 0x02000000, 0x05000000, 1, (1 << 15) | (1 << 10));

        dma.triggerImmediateTransfers();

        assertEquals(0x001F, memory.read16(0x05000000));
        assertEquals(0x03E0, memory.read16(0x05000002));
    }

    @Test
    void sourceAndDestinationCanDecrement() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0x1111);
        memory.write16(0x02000002, 0x2222);
        setupDma(memory, 1, 0x02000002, 0x03000002, 2, (1 << 15) | (1 << 7) | (1 << 5));

        dma.triggerImmediateTransfers();

        assertEquals(0x1111, memory.read16(0x03000000));
        assertEquals(0x2222, memory.read16(0x03000002));
    }

    @Test
    void fixedDestinationFillsMemory() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0x1111);
        memory.write16(0x02000002, 0x2222);
        setupDma(memory, 2, 0x02000000, 0x03000000, 2, (1 << 15) | (2 << 5));

        dma.triggerImmediateTransfers();

        assertEquals(0x2222, memory.read16(0x03000000));
    }

    @Test
    void reloadDestinationRestoresOriginalAddress() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0x1111);
        setupDma(memory, 0, 0x02000000, 0x03000000, 1, (1 << 15) | (3 << 5));

        dma.triggerImmediateTransfers();

        assertEquals(0x03000000, memory.read32(0x040000B4));
    }

    @Test
    void zeroCountUsesChannelSpecificMaximum() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0xCAFE);
        setupDma(memory, 0, 0x02000000, 0x03000000, 0, (1 << 15) | (2 << 7) | (2 << 5));

        dma.triggerImmediateTransfers();

        assertEquals(0xCAFE, memory.read16(0x03000000));
        assertEquals(0x02000000, memory.read32(0x040000B0));
        assertEquals(0x03000000, memory.read32(0x040000B4));
    }

    @Test
    void nonImmediateTransferIsNotTriggeredByImmediatePass() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaDmaController dma = new GbaDmaController(memory);
        memory.write16(0x02000000, 0x1234);
        setupDma(memory, 0, 0x02000000, 0x03000000, 1, (1 << 15) | (1 << 12));

        dma.triggerImmediateTransfers();

        assertEquals(0, memory.read16(0x03000000));
        assertEquals(1 << 15, memory.read16(0x040000BA) & (1 << 15));
    }

    @Test
    void requestsInterruptWhenTransferCompletesWithIrqEnabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        GbaDmaController dma = new GbaDmaController(memory, interrupts);
        memory.write16(0x02000000, 0x1234);
        setupDma(memory, 2, 0x02000000, 0x03000000, 1, (1 << 15) | (1 << 14));

        dma.triggerImmediateTransfers();

        assertEquals(GbaInterrupt.DMA2.mask(), memory.read16(GbaInterruptController.IF));
    }

    private static void setupDma(GbaMemory memory, int channel, int source, int destination, int count, int control) {
        int base = 0x040000B0 + channel * 12;
        memory.write32(base, source);
        memory.write32(base + 4, destination);
        memory.write16(base + 8, count);
        memory.write16(base + 10, control);
    }
}
