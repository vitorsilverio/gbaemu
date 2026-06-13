package dev.vitorsilverio.gbaemu.link;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.serial.GbaSerial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Two serial peripherals wired through an in-memory transport exchange a multiplayer-mode
/// word, exercising the {@link GbaLink} protocol without real sockets.
class GbaLinkTest {

    private static final int SIOMULTI0 = 0x04000120;
    private static final int SIOMULTI1 = 0x04000122;
    private static final int SIOCNT = 0x04000128;
    private static final int SIOMLT_SEND = 0x0400012A;
    private static final int RCNT = 0x04000134;

    private static final int MODE_MULTIPLAYER = 2 << 12;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;

    private record Unit(GbaSerial serial, GbaInterruptController interrupts) {
    }

    private static Unit unit(PhysicalConnection connection) {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaSerial serial = new GbaSerial(interrupts);
        serial.link().useConnection(connection);
        serial.writeHalfWord(RCNT, 0);
        return new Unit(serial, interrupts);
    }

    private static boolean serialIrq(GbaInterruptController interrupts) {
        return (interrupts.readHalfWord(GbaInterruptController.IF) & GbaInterrupt.SERIAL.mask()) != 0;
    }

    @Test
    void parentAndChildExchangeWordsAndBothGetAllSlots() {
        InMemoryConnectionPair pair = new InMemoryConnectionPair();
        Unit host = unit(pair.left());   // hosting -> parent, id 0
        Unit child = unit(pair.right()); // joined  -> child, id 1

        // Child publishes its word and arms its transfer (waits for the parent to drive).
        child.serial.writeHalfWord(SIOMLT_SEND, 0xBBBB);
        child.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);

        // Parent publishes its word and starts; ticking drives completion + broadcast.
        host.serial.writeHalfWord(SIOMLT_SEND, 0xAAAA);
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
        host.serial.tick(10_000);

        // Parent latched the assembled words.
        assertEquals(0xAAAA, host.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xBBBB, host.serial.readHalfWord(SIOMULTI1));
        assertEquals(0, (host.serial.readHalfWord(SIOCNT) >>> 4) & 0x3, "host is id 0");
        assertEquals(0, host.serial.readHalfWord(SIOCNT) & START);
        assertTrue(serialIrq(host.interrupts));

        // Child received the broadcast synchronously and completed as id 1.
        assertEquals(0xAAAA, child.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xBBBB, child.serial.readHalfWord(SIOMULTI1));
        assertEquals(1, (child.serial.readHalfWord(SIOCNT) >>> 4) & 0x3, "child is id 1");
        assertEquals(0, child.serial.readHalfWord(SIOCNT) & START);
        assertTrue(serialIrq(child.interrupts));
    }

    @Test
    void siocntReportsConnectionAndChildStatusBits() {
        InMemoryConnectionPair pair = new InMemoryConnectionPair();
        Unit host = unit(pair.left());
        Unit child = unit(pair.right());
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER);
        child.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER);

        // Bit3 (SD) = good connection on both; bit2 (SI) = child only.
        assertTrue((host.serial.readHalfWord(SIOCNT) & 0x08) != 0);
        assertEquals(0, host.serial.readHalfWord(SIOCNT) & 0x04, "host is parent");
        assertTrue((child.serial.readHalfWord(SIOCNT) & 0x08) != 0);
        assertTrue((child.serial.readHalfWord(SIOCNT) & 0x04) != 0, "child sets the SI bit");
    }
}
