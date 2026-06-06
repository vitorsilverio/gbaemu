package dev.vitorsilverio.gbaemu.dma;

import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaBus;
import dev.vitorsilverio.gbaemu.memory.GbaEwram;
import dev.vitorsilverio.gbaemu.memory.GbaIwram;
import dev.vitorsilverio.gbaemu.video.GbaVideoMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaDmaControllerTest {

    private record Ctx(GbaBus bus, GbaAudio audio, GbaDmaController dma, GbaInterruptController interrupts) {}

    private static Ctx createCtx() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaAudio audio = new GbaAudio();
        GbaBus bus = new GbaBus();
        GbaDmaController dma = new GbaDmaController(bus, interrupts);
        bus.add(audio);
        bus.add(dma);
        bus.add(new GbaEwram());
        bus.add(new GbaIwram());
        bus.add(new GbaVideoMemory(() -> 0));
        return new Ctx(bus, audio, dma, interrupts);
    }

    private static void setupDma(GbaDmaController dma, int channel, int source, int destination, int count, int control) {
        int base = 0x040000B0 + channel * 12;
        dma.writeWord(base, source);
        dma.writeWord(base + 4, destination);
        dma.writeHalfWord(base + 8, count);
        dma.writeHalfWord(base + 10, control);
    }

    @Test
    void immediateHalfwordTransferCopiesMemoryAndDisablesChannel() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1234);
        ctx.bus.write16(0x02000002, 0x5678);
        setupDma(ctx.dma, 0, 0x02000000, 0x03000000, 2, 1 << 15);

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0x1234, ctx.bus.read16(0x03000000));
        assertEquals(0x5678, ctx.bus.read16(0x03000002));
        assertEquals(0, ctx.dma.readHalfWord(0x040000BA) & (1 << 15));
    }

    @Test
    void immediateWordTransferCopiesToPaletteRam() {
        Ctx ctx = createCtx();
        ctx.bus.write32(0x02000000, 0x03E0001F);
        setupDma(ctx.dma, 3, 0x02000000, 0x05000000, 1, (1 << 15) | (1 << 10));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0x001F, ctx.bus.read16(0x05000000));
        assertEquals(0x03E0, ctx.bus.read16(0x05000002));
    }

    @Test
    void sourceAndDestinationCanDecrement() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1111);
        ctx.bus.write16(0x02000002, 0x2222);
        setupDma(ctx.dma, 1, 0x02000002, 0x03000002, 2, (1 << 15) | (1 << 7) | (1 << 5));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0x1111, ctx.bus.read16(0x03000000));
        assertEquals(0x2222, ctx.bus.read16(0x03000002));
    }

    @Test
    void fixedDestinationFillsMemory() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1111);
        ctx.bus.write16(0x02000002, 0x2222);
        setupDma(ctx.dma, 2, 0x02000000, 0x03000000, 2, (1 << 15) | (2 << 5));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0x2222, ctx.bus.read16(0x03000000));
    }

    @Test
    void reloadDestinationRestoresOriginalAddress() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1111);
        setupDma(ctx.dma, 0, 0x02000000, 0x03000000, 1, (1 << 15) | (3 << 5));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0x03000000, ctx.dma.readWord(0x040000B4));
    }

    @Test
    void zeroCountUsesChannelSpecificMaximum() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0xCAFE);
        setupDma(ctx.dma, 0, 0x02000000, 0x03000000, 0, (1 << 15) | (2 << 7) | (2 << 5));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0xCAFE, ctx.bus.read16(0x03000000));
        assertEquals(0x02000000, ctx.dma.readWord(0x040000B0));
        assertEquals(0x03000000, ctx.dma.readWord(0x040000B4));
    }

    @Test
    void nonImmediateTransferIsNotTriggeredByImmediatePass() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1234);
        setupDma(ctx.dma, 0, 0x02000000, 0x03000000, 1, (1 << 15) | (1 << 12));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0, ctx.bus.read16(0x03000000));
        assertEquals(1 << 15, ctx.dma.readHalfWord(0x040000BA) & (1 << 15));
    }

    @Test
    void vblankTransferRunsOnlyWhenVblankIsTriggered() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1234);
        setupDma(ctx.dma, 0, 0x02000000, 0x03000000, 1, (1 << 15) | (1 << 12));

        ctx.dma.triggerHblankTransfers();

        assertEquals(0, ctx.bus.read16(0x03000000));

        ctx.dma.triggerVblankTransfers();

        assertEquals(0x1234, ctx.bus.read16(0x03000000));
        assertEquals(0, ctx.dma.readHalfWord(0x040000BA) & (1 << 15));
    }

    @Test
    void hblankRepeatTransferKeepsChannelEnabledAndReloadsDestination() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1111);
        ctx.bus.write16(0x02000002, 0x2222);
        setupDma(ctx.dma, 1, 0x02000000, 0x03000000, 1, (1 << 15) | (2 << 12) | (1 << 9) | (3 << 5));

        ctx.dma.triggerHblankTransfers();

        assertEquals(0x1111, ctx.bus.read16(0x03000000));
        assertEquals(1 << 15, ctx.dma.readHalfWord(0x040000C6) & (1 << 15));
        assertEquals(0x03000000, ctx.dma.readWord(0x040000C0));

        ctx.dma.triggerHblankTransfers();

        assertEquals(0x2222, ctx.bus.read16(0x03000000));
    }

    @Test
    void requestsInterruptWhenTransferCompletesWithIrqEnabled() {
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0x1234);
        setupDma(ctx.dma, 2, 0x02000000, 0x03000000, 1, (1 << 15) | (1 << 14));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(GbaInterrupt.DMA2.mask(), ctx.interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void specialDmaRefillsDirectSoundFifoWithFourWords() {
        Ctx ctx = createCtx();
        ctx.bus.write32(0x02000000, 0x44332211);
        ctx.bus.write32(0x02000004, 0x88776655);
        ctx.bus.write32(0x02000008, 0xCCBBAA99);
        ctx.bus.write32(0x0200000C, 0x00FFEEDD);
        setupDma(ctx.dma, 1, 0x02000000, GbaAudio.FIFO_A, 0, (1 << 15) | (3 << 12) | (1 << 10));

        ctx.dma.triggerAudioFifoTransfers(GbaAudio.FIFO_A_REQUEST);

        assertEquals(16, ctx.audio.fifoASize());
        assertEquals(0x02000010, ctx.dma.readWord(0x040000BC));
        assertEquals(GbaAudio.FIFO_A, ctx.dma.readWord(0x040000C0));
        assertEquals(0x11, ctx.audio.popFifoA());
        assertEquals(0x22, ctx.audio.popFifoA());
    }
}
