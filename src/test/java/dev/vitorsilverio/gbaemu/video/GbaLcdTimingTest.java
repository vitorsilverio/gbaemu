package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaLcdTimingTest {
    @Test
    void startsAtScanlineZeroAndSetsVcountCoincidenceWhenConfiguredToZero() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        assertEquals(0, timing.readHalfWord(0x04000006));
        assertEquals(1 << 2, timing.readHalfWord(0x04000004) & 0x7);
    }

    @Test
    void advancesVcountAfterOneScanline() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE);

        assertEquals(1, timing.scanline());
        assertEquals(1, timing.readHalfWord(0x04000006));
    }

    @Test
    void setsHblankDuringTheEndOfTheScanline() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        timing.tick(960);

        assertEquals(1 << 1, timing.readHalfWord(0x04000004) & (1 << 1));
    }

    @Test
    void setsVblankFromScanline160Through227() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(160, timing.readHalfWord(0x04000006));
        assertEquals(1, timing.readHalfWord(0x04000004) & 1);
    }

    @Test
    void wrapsAfterTheLastScanline() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 228);

        assertEquals(0, timing.scanline());
        assertEquals(0, timing.readHalfWord(0x04000006));
    }

    @Test
    void preservesDispstatInterruptEnableAndCoincidenceSettings() {
        GbaLcdTiming timing = new GbaLcdTiming(null);
        timing.writeHalfWord(0x04000004, (42 << 8) | (1 << 3) | (1 << 4) | (1 << 5));

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 42);

        assertEquals((42 << 8) | (1 << 3) | (1 << 4) | (1 << 5) | (1 << 2),
                timing.readHalfWord(0x04000004));
    }

    @Test
    void requestsVblankInterruptOnRisingEdgeWhenEnabledInDispstat() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaLcdTiming timing = new GbaLcdTiming(interrupts);
        timing.writeHalfWord(0x04000004, 1 << 3);

        timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(GbaInterrupt.VBLANK.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void requestsHblankInterruptOnRisingEdgeWhenEnabledInDispstat() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaLcdTiming timing = new GbaLcdTiming(interrupts);
        timing.writeHalfWord(0x04000004, 1 << 4);

        timing.tick(960);

        assertEquals(GbaInterrupt.HBLANK.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void reportsVblankAndHblankStartEvents() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        GbaLcdTiming.Events hblank = timing.tick(960);

        assertEquals(true, hblank.hblankStarted());
        assertEquals(1, hblank.hblankStartedCount());

        GbaLcdTiming.Events vblank = timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 160);

        assertEquals(true, vblank.vblankStarted());
        assertEquals(1, vblank.vblankStartedCount());
    }

    @Test
    void countsEveryHblankStartCrossedByLargeTicks() {
        GbaLcdTiming timing = new GbaLcdTiming(null);

        GbaLcdTiming.Events events = timing.tick(GbaLcdTiming.CYCLES_PER_SCANLINE * 3);

        assertEquals(3, events.hblankStartedCount());
    }
}
