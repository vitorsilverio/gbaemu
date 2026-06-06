package dev.vitorsilverio.gbaemu.interrupt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaInterruptControllerTest {
    @Test
    void pendingRequiresImeIeAndIf() {
        GbaInterruptController interrupts = new GbaInterruptController();

        interrupts.request(GbaInterrupt.VBLANK);
        assertFalse(interrupts.pending());

        interrupts.enable(GbaInterrupt.VBLANK);
        assertFalse(interrupts.pending());

        interrupts.setMasterEnable(true);
        assertTrue(interrupts.pending());
    }

    @Test
    void acknowledgeClearsRequestedBitsThroughIfWriteOneToClear() {
        GbaInterruptController interrupts = new GbaInterruptController();
        interrupts.request(GbaInterrupt.VBLANK);
        interrupts.request(GbaInterrupt.HBLANK);

        interrupts.acknowledge(GbaInterrupt.VBLANK.mask());

        assertEquals(GbaInterrupt.HBLANK.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void disableRemovesInterruptFromPendingMask() {
        GbaInterruptController interrupts = new GbaInterruptController();
        interrupts.setMasterEnable(true);
        interrupts.enable(GbaInterrupt.VBLANK);
        interrupts.request(GbaInterrupt.VBLANK);

        interrupts.disable(GbaInterrupt.VBLANK);

        assertFalse(interrupts.pending());
    }
}
