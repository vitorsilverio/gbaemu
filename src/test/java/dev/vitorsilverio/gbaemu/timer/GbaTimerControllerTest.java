package dev.vitorsilverio.gbaemu.timer;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaTimerControllerTest {
    @Test
    void enabledTimerStartsFromReloadAndTicksWithPrescalerOne() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0xFFFC);
        memory.write16(0x04000102, 1 << 7);

        timers.tick(3);

        assertEquals(0xFFFF, memory.read16(0x04000100));
    }

    @Test
    void overflowReloadsCounter() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0xFFFE);
        memory.write16(0x04000102, 1 << 7);

        timers.tick(2);

        assertEquals(0xFFFE, memory.read16(0x04000100));
    }

    @Test
    void bulkTickCountsMultipleOverflows() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0xFFFE);
        memory.write16(0x04000102, 1 << 7);

        timers.tick(6);

        assertEquals(0xFFFE, memory.read16(0x04000100));
        assertEquals(3, timers.overflowCount(0));
    }

    @Test
    void overflowRequestsInterruptWhenEnabled() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        GbaTimerController timers = new GbaTimerController(memory, interrupts);
        memory.write16(0x04000100, 0xFFFF);
        memory.write16(0x04000102, (1 << 7) | (1 << 6));

        timers.tick(1);

        assertEquals(GbaInterrupt.TIMER0.mask(), memory.read16(GbaInterruptController.IF));
    }

    @Test
    void prescalerSixtyFourConsumesSixtyFourCyclesPerIncrement() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0);
        memory.write16(0x04000102, (1 << 7) | 1);

        timers.tick(63);
        assertEquals(0, memory.read16(0x04000100));

        timers.tick(1);
        assertEquals(1, memory.read16(0x04000100));
    }

    @Test
    void cascadeTimerIncrementsWhenPreviousTimerOverflows() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0xFFFF);
        memory.write16(0x04000102, 1 << 7);
        memory.write16(0x04000104, 0);
        memory.write16(0x04000106, (1 << 7) | (1 << 2));

        timers.tick(1);

        assertEquals(1, memory.read16(0x04000104));
    }

    @Test
    void disabledTimerDoesNotTick() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaTimerController timers = new GbaTimerController(memory);
        memory.write16(0x04000100, 0x1234);

        timers.tick(1000);

        assertEquals(0x1234, memory.read16(0x04000100));
    }
}
