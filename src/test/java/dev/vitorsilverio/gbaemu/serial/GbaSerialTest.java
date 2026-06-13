package dev.vitorsilverio.gbaemu.serial;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaSerialTest {

    private static final int SIOMULTI0 = 0x04000120;
    private static final int SIOMULTI1 = 0x04000122;
    private static final int SIOMULTI2 = 0x04000124;
    private static final int SIOMULTI3 = 0x04000126;
    private static final int SIOCNT = 0x04000128;
    private static final int SIOMLT_SEND = 0x0400012A;
    private static final int RCNT = 0x04000134;

    private static final int MODE_MULTIPLAYER = 2 << 12;
    private static final int MODE_NORMAL8 = 0;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;

    private record Ctx(GbaSerial serial, GbaInterruptController interrupts) {
    }

    private static Ctx create() {
        GbaInterruptController interrupts = new GbaInterruptController();
        return new Ctx(new GbaSerial(interrupts), interrupts);
    }

    private static boolean serialIrqRaised(GbaInterruptController interrupts) {
        return (interrupts.readHalfWord(GbaInterruptController.IF) & GbaInterrupt.SERIAL.mask()) != 0;
    }

    @Test
    void soloMultiplayerTransferReturnsOwnWordAndAbsentPeers() {
        Ctx ctx = create();
        ctx.serial.writeHalfWord(RCNT, 0);                          // SIO mode group
        ctx.serial.writeHalfWord(SIOMLT_SEND, 0x1234);
        ctx.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);

        assertNotEquals(0, ctx.serial.readHalfWord(SIOCNT) & START, "transfer should be busy until it completes");

        ctx.serial.tick(10_000); // longer than any baud's transfer time

        assertEquals(0x1234, ctx.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xFFFF, ctx.serial.readHalfWord(SIOMULTI1));
        assertEquals(0xFFFF, ctx.serial.readHalfWord(SIOMULTI2));
        assertEquals(0xFFFF, ctx.serial.readHalfWord(SIOMULTI3));
        assertEquals(0, ctx.serial.readHalfWord(SIOCNT) & START, "start/busy bit clears on completion");
        assertEquals(0, (ctx.serial.readHalfWord(SIOCNT) >>> 4) & 0x3, "a lone unit is parent id 0");
        assertTrue(serialIrqRaised(ctx.interrupts));
    }

    @Test
    void multiplayerTransferDoesNotCompleteBeforeItsTime() {
        Ctx ctx = create();
        ctx.serial.writeHalfWord(RCNT, 0);
        ctx.serial.writeHalfWord(SIOMLT_SEND, 0x00AB);
        // baud 0 (~9600) takes the longest; a small tick must not finish it.
        ctx.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | START);

        ctx.serial.tick(64);

        assertNotEquals(0, ctx.serial.readHalfWord(SIOCNT) & START, "still busy after a short tick");
    }

    @Test
    void normalModeCompletesAsDisconnected() {
        Ctx ctx = create();
        ctx.serial.writeHalfWord(RCNT, 0);
        ctx.serial.writeHalfWord(SIOCNT, MODE_NORMAL8 | IRQ_ENABLE | START);

        // Normal mode with nothing attached finishes immediately with open-bus data.
        assertEquals(0xFFFF, ctx.serial.readHalfWord(SIOMULTI0));
        assertEquals(0, ctx.serial.readHalfWord(SIOCNT) & START);
        assertTrue(serialIrqRaised(ctx.interrupts));
    }

    @Test
    void generalPurposeModeIgnoresStart() {
        Ctx ctx = create();
        ctx.serial.writeHalfWord(RCNT, 0x8000); // RCNT bit15 = general-purpose, not SIO
        ctx.serial.writeHalfWord(SIOMLT_SEND, 0x4321);
        ctx.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | START);
        ctx.serial.tick(10_000);

        assertFalse(serialIrqRaised(ctx.interrupts), "no SIO transfer happens outside SIO mode");
    }

    @Test
    void siodata32WordAccess() {
        Ctx ctx = create();
        ctx.serial.writeWord(SIOMULTI0, 0xCAFEBABE);
        assertEquals(0xBABE, ctx.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xCAFE, ctx.serial.readHalfWord(SIOMULTI1));
        assertEquals(0xCAFEBABE, ctx.serial.readWord(SIOMULTI0));
    }
}
