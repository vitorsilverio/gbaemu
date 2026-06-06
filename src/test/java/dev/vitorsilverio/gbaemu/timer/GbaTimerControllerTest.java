package dev.vitorsilverio.gbaemu.timer;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaTimerControllerTest {
    @Test
    void enabledTimerStartsFromReloadAndTicksWithPrescalerOne() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0xFFFC);
        timers.writeHalfWord(0x04000102, 1 << 7);

        timers.tick(3);

        assertEquals(0xFFFF, timers.readHalfWord(0x04000100));
    }

    @Test
    void overflowReloadsCounter() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0xFFFE);
        timers.writeHalfWord(0x04000102, 1 << 7);

        timers.tick(2);

        assertEquals(0xFFFE, timers.readHalfWord(0x04000100));
    }

    @Test
    void bulkTickCountsMultipleOverflows() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0xFFFE);
        timers.writeHalfWord(0x04000102, 1 << 7);

        timers.tick(6);

        assertEquals(0xFFFE, timers.readHalfWord(0x04000100));
        assertEquals(3, timers.overflowCount(0));
    }

    @Test
    void overflowRequestsInterruptWhenEnabled() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaTimerController timers = new GbaTimerController(interrupts);
        timers.writeHalfWord(0x04000100, 0xFFFF);
        timers.writeHalfWord(0x04000102, (1 << 7) | (1 << 6));

        timers.tick(1);

        assertEquals(GbaInterrupt.TIMER0.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void prescalerSixtyFourConsumesSixtyFourCyclesPerIncrement() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0);
        timers.writeHalfWord(0x04000102, (1 << 7) | 1);

        timers.tick(63);
        assertEquals(0, timers.readHalfWord(0x04000100));

        timers.tick(1);
        assertEquals(1, timers.readHalfWord(0x04000100));
    }

    @Test
    void cascadeTimerIncrementsWhenPreviousTimerOverflows() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0xFFFF);
        timers.writeHalfWord(0x04000102, 1 << 7);
        timers.writeHalfWord(0x04000104, 0);
        timers.writeHalfWord(0x04000106, (1 << 7) | (1 << 2));

        timers.tick(1);

        assertEquals(1, timers.readHalfWord(0x04000104));
    }

    @Test
    void disabledTimerDoesNotTick() {
        GbaTimerController timers = new GbaTimerController(null);
        timers.writeHalfWord(0x04000100, 0x1234);

        timers.tick(1000);

        assertEquals(0x1234, timers.readHalfWord(0x04000100));
    }
}
