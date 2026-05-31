package dev.vitorsilverio.gbaemu.interrupt;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaInterruptControllerTest {
    @Test
    void pendingRequiresImeIeAndIf() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);

        interrupts.request(GbaInterrupt.VBLANK);
        assertFalse(interrupts.pending());

        interrupts.enable(GbaInterrupt.VBLANK);
        assertFalse(interrupts.pending());

        interrupts.setMasterEnable(true);
        assertTrue(interrupts.pending());
    }

    @Test
    void acknowledgeClearsRequestedBitsThroughIfWriteOneToClear() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        interrupts.request(GbaInterrupt.VBLANK);
        interrupts.request(GbaInterrupt.HBLANK);

        interrupts.acknowledge(GbaInterrupt.VBLANK.mask());

        assertEquals(GbaInterrupt.HBLANK.mask(), memory.read16(GbaInterruptController.IF));
    }

    @Test
    void disableRemovesInterruptFromPendingMask() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        interrupts.setMasterEnable(true);
        interrupts.enable(GbaInterrupt.VBLANK);
        interrupts.request(GbaInterrupt.VBLANK);

        interrupts.disable(GbaInterrupt.VBLANK);

        assertFalse(interrupts.pending());
    }
}
