package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaLcdTimingTest {
    @Test
    void startsAtScanlineZeroAndSetsVcountCoincidenceWhenConfiguredToZero() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        new GbaLcdTiming(memory);

        assertEquals(0, memory.read16(0x04000006));
        assertEquals(1 << 2, memory.read16(0x04000004) & 0x7);
    }

    @Test
    void advancesVcountAfterOneScanline() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE);

        assertEquals(1, timing.scanline());
        assertEquals(1, memory.read16(0x04000006));
    }

    @Test
    void setsHblankDuringTheEndOfTheScanline() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        timing.tick(960);

        assertEquals(1 << 1, memory.read16(0x04000004) & (1 << 1));
    }

    @Test
    void setsVblankFromScanline160Through227() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(160, memory.read16(0x04000006));
        assertEquals(1, memory.read16(0x04000004) & 1);
    }

    @Test
    void wrapsAfterTheLastScanline() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 228);

        assertEquals(0, timing.scanline());
        assertEquals(0, memory.read16(0x04000006));
    }

    @Test
    void preservesDispstatInterruptEnableAndCoincidenceSettings() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        memory.write16(0x04000004, (42 << 8) | (1 << 3) | (1 << 4) | (1 << 5));
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 42);

        assertEquals((42 << 8) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 2),
                memory.read16(0x04000004));
    }

    @Test
    void requestsVblankInterruptOnRisingEdgeWhenEnabledInDispstat() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        memory.write16(0x04000004, 1 << 3);
        GbaLcdTiming timing = new GbaLcdTiming(memory, interrupts);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(GbaInterrupt.VBLANK.mask(), memory.read16(GbaInterruptController.IF));
    }

    @Test
    void requestsHblankInterruptOnRisingEdgeWhenEnabledInDispstat() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        memory.write16(0x04000004, 1 << 4);
        GbaLcdTiming timing = new GbaLcdTiming(memory, interrupts);

        timing.tick(960);

        assertEquals(GbaInterrupt.HBLANK.mask(), memory.read16(GbaInterruptController.IF));
    }

    @Test
    void reportsVblankAndHblankStartEvents() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        GbaLcdTiming.Events hblank = timing.tick(960);

        assertEquals(true, hblank.hblankStarted());
        assertEquals(1, hblank.hblankStartedCount());

        GbaLcdTiming.Events vblank = timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(true, vblank.vblankStarted());
        assertEquals(1, vblank.vblankStartedCount());
    }

    @Test
    void countsEveryHblankStartCrossedByLargeTicks() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaLcdTiming timing = new GbaLcdTiming(memory);

        GbaLcdTiming.Events events = timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 3);

        assertEquals(3, events.hblankStartedCount());
    }
}
