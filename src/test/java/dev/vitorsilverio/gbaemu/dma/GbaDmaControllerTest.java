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
    void wordTransferForcesSourceAndDestinationAlignment() {
        Ctx ctx = createCtx();
        ctx.bus.write32(0x02000000, 0xAABBCCDD); // aligned word at the real source
        // Odd source (0x02000001) + unaligned dest (0x03000002), 32-bit, 1 word, immediate.
        // The GBA DMA must align both down — Metroid Fusion passes a THUMB routine's odd
        // address as a 32-bit DMA source and relies on this (else the copy is byte-shifted).
        setupDma(ctx.dma, 3, 0x02000001, 0x03000002, 1, (1 << 15) | (1 << 10));

        ctx.dma.triggerImmediateTransfers();

        assertEquals(0xAABBCCDD, ctx.bus.read32(0x03000000));
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
    void backToBackImmediateFillsOnSameChannelBothRun() {
        // MainMenuGpuInit arms three immediate DMA fills on channel 3 back-to-back (VRAM, then
        // OAM, then PLTT) within a single straight-line code block. Each must run the instant it
        // is enabled, before the next arm overwrites the channel registers. If immediate DMAs were
        // deferred to a single per-block triggerImmediateTransfers(), only the last config would
        // run and the earlier fills (the VRAM clear) would be silently dropped -- which left the
        // stale green title tiles on the Oak-intro background.
        Ctx ctx = createCtx();
        ctx.bus.write16(0x02000000, 0xABCD); // fixed fill source

        // First immediate fill -> region A
        setupDma(ctx.dma, 3, 0x02000000, 0x02001000, 1, (1 << 15) | (2 << 7)); // enable, src fixed
        // Second immediate fill -> region B, reusing (clobbering) channel 3's registers
        setupDma(ctx.dma, 3, 0x02000000, 0x02002000, 1, (1 << 15) | (2 << 7));

        // The per-block hardware tick happens only now -- both fills must already have run at arm time.
        ctx.dma.triggerImmediateTransfers();

        assertEquals(0xABCD, ctx.bus.read16(0x02001000)); // would be 0 if the first fill were dropped
        assertEquals(0xABCD, ctx.bus.read16(0x02002000));
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
        // SAD is write-only on hardware: the register keeps the buffer start; only the
        // internal pointer advances. (Previously this wrongly asserted 0x02000010.)
        assertEquals(0x02000000, ctx.dma.readWord(0x040000BC));
        assertEquals(GbaAudio.FIFO_A, ctx.dma.readWord(0x040000C0));
        assertEquals(0x11, ctx.audio.popFifoA());
        assertEquals(0x22, ctx.audio.popFifoA());
    }

    @Test
    void reenablingSoundDmaRestartsFromTheBufferStart() {
        Ctx ctx = createCtx();
        ctx.bus.write32(0x02000000, 0x44332211);
        ctx.bus.write32(0x02000004, 0x88776655);
        ctx.bus.write32(0x02000008, 0xCCBBAA99);
        ctx.bus.write32(0x0200000C, 0x00FFEEDD);
        int control = (1 << 15) | (1 << 9) | (3 << 12) | (1 << 10); // enable, repeat, special, word
        setupDma(ctx.dma, 1, 0x02000000, GbaAudio.FIFO_A, 0, control);

        ctx.dma.triggerAudioFifoTransfers(GbaAudio.FIFO_A_REQUEST);
        for (int i = 0; i < 16; i++) {
            ctx.audio.popFifoA(); // drain the first refill; the internal pointer advanced
        }

        // The game (m4a) re-enables the DMA every frame WITHOUT rewriting the source,
        // relying on the enable to reload the buffer start from the SAD register.
        ctx.dma.writeHalfWord(0x040000C6, 0);        // disable
        ctx.dma.writeHalfWord(0x040000C6, control);  // re-enable -> reload internal source

        ctx.dma.triggerAudioFifoTransfers(GbaAudio.FIFO_A_REQUEST);

        // Must replay from the buffer start, not continue past it into adjacent memory.
        assertEquals(0x11, ctx.audio.popFifoA());
        assertEquals(0x22, ctx.audio.popFifoA());
        assertEquals(0x33, ctx.audio.popFifoA());
        assertEquals(0x44, ctx.audio.popFifoA());
    }
}
