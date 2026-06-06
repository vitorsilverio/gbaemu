package dev.vitorsilverio.gbaemu.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaSystemControlTest {
    @Test
    void postBootFlagIsStoredInPostflgBitZero() {
        GbaSystemControl system = new GbaSystemControl();

        system.setPostBootFlag(true);

        assertTrue(system.postBootFlag());
        assertEquals(1, system.readByte(GbaSystemControl.POSTFLG));
    }

    @Test
    void waitcntRoundTripsThroughMemory() {
        GbaSystemControl system = new GbaSystemControl();

        system.setWaitControl(0x4317);

        assertEquals(0x4317, system.waitControl());
    }

    @Test
    void haltcntBitSevenChoosesHaltOrStop() {
        GbaSystemControl system = new GbaSystemControl();

        system.writeHaltControl(0);
        assertTrue(system.halted());
        assertFalse(system.stopped());

        system.writeHaltControl(0x80);
        assertFalse(system.halted());
        assertTrue(system.stopped());
    }

    @Test
    void resumeClearsLowPowerState() {
        GbaSystemControl system = new GbaSystemControl();
        system.writeHaltControl(0);

        system.resume();

        assertFalse(system.halted());
        assertFalse(system.stopped());
    }
}
