package dev.vitorsilverio.gbaemu.system;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaSystemControlTest {
    @Test
    void postBootFlagIsStoredInPostflgBitZero() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaSystemControl system = new GbaSystemControl(memory);

        system.setPostBootFlag(true);

        assertTrue(system.postBootFlag());
        assertEquals(1, memory.read8(GbaSystemControl.POSTFLG));
    }

    @Test
    void waitcntRoundTripsThroughMemory() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaSystemControl system = new GbaSystemControl(memory);

        system.setWaitControl(0x4317);

        assertEquals(0x4317, system.waitControl());
    }

    @Test
    void haltcntBitSevenChoosesHaltOrStop() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaSystemControl system = new GbaSystemControl(memory);

        system.writeHaltControl(0);
        assertTrue(system.halted());
        assertFalse(system.stopped());

        system.writeHaltControl(0x80);
        assertFalse(system.halted());
        assertTrue(system.stopped());
    }

    @Test
    void resumeClearsLowPowerState() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaSystemControl system = new GbaSystemControl(memory);
        system.writeHaltControl(0);

        system.resume();

        assertFalse(system.halted());
        assertFalse(system.stopped());
    }
}
