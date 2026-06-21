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

    private static final int SIODATA32 = 0x04000120; // aliases SIOMULTI0/1 in Normal 32-bit mode
    private static final int MODE_MULTIPLAYER = 2 << 12;
    private static final int MODE_NORMAL32 = 1 << 12;
    private static final int NORMAL_INTERNAL_CLOCK = 0x01;
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
    void reactiveChildIsInterruptedOnEveryParentTransferWithoutArming() {
        InMemoryConnectionPair pair = new InMemoryConnectionPair();
        Unit host = unit(pair.left());
        Unit child = unit(pair.right());

        // The child sets up multiplayer mode with the serial IRQ enabled and publishes a word,
        // but never writes the start bit — real children are reactive; the parent drives every
        // transfer (this is the Mario Kart link-screen pattern that used to hang).
        child.serial.writeHalfWord(SIOMLT_SEND, 0xBBBB);
        child.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE);

        // Parent drives a transfer.
        host.serial.writeHalfWord(SIOMLT_SEND, 0xAAAA);
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
        host.serial.tick(10_000);

        // The child received the data and a SERIAL IRQ even though it never armed a transfer.
        assertEquals(0xAAAA, child.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xBBBB, child.serial.readHalfWord(SIOMULTI1));
        assertEquals(1, (child.serial.readHalfWord(SIOCNT) >>> 4) & 0x3, "child learns it is id 1");
        assertTrue(serialIrq(child.interrupts), "a parent-driven transfer must interrupt the reactive child");
    }

    @Test
    void normalModeMasterAndSlaveSwapWordsOverTheLink() {
        InMemoryConnectionPair pair = new InMemoryConnectionPair();
        Unit master = unit(pair.left());
        Unit slave = unit(pair.right());

        // Slave arms a Normal 32-bit transfer (external clock, bit0=0) carrying its outgoing word.
        slave.serial.writeWord(SIODATA32, 0x11112222);
        slave.serial.writeHalfWord(SIOCNT, MODE_NORMAL32 | IRQ_ENABLE | START);

        // Master drives a Normal 32-bit transfer (internal clock, bit0=1) carrying its word.
        master.serial.writeWord(SIODATA32, 0xAAAABBBB);
        master.serial.writeHalfWord(SIOCNT, MODE_NORMAL32 | NORMAL_INTERNAL_CLOCK | IRQ_ENABLE | START);
        master.serial.tick(10_000);

        // The two SIODATA32 registers swapped, and both units took a SERIAL IRQ.
        assertEquals(0x11112222, master.serial.readWord(SIODATA32), "master receives the slave's word");
        assertEquals(0xAAAABBBB, slave.serial.readWord(SIODATA32), "slave receives the master's word");
        assertEquals(0, master.serial.readHalfWord(SIOCNT) & START, "master transfer completes");
        assertEquals(0, slave.serial.readHalfWord(SIOCNT) & START, "slave transfer completes");
        assertTrue(serialIrq(master.interrupts));
        assertTrue(serialIrq(slave.interrupts));
    }

    @Test
    void aLiveConnectionCarriesToANewSerialAcrossARestart() {
        InMemoryConnectionPair pair = new InMemoryConnectionPair();
        Unit host = unit(pair.left());
        Unit childBefore = unit(pair.right());
        assertTrue(childBefore.serial.link().isConnected());

        // Simulate a console restart on the child: hand its live connection to a brand-new serial
        // (the "cable" stays plugged in across the reboot — GbaDesktopApp#restart does this).
        PhysicalConnection live = childBefore.serial.link().connection();
        GbaInterruptController newChildIrq = new GbaInterruptController();
        GbaSerial newChild = new GbaSerial(newChildIrq);
        newChild.link().useConnection(live);
        newChild.writeHalfWord(RCNT, 0);
        assertTrue(newChild.link().isConnected(), "the carried connection stays connected on the new serial");

        // The freshly-"booted" child participates: a parent transfer reaches it and interrupts it.
        newChild.writeHalfWord(SIOMLT_SEND, 0xBBBB);
        newChild.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE);
        host.serial.writeHalfWord(SIOMLT_SEND, 0xAAAA);
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
        host.serial.tick(10_000);

        assertEquals(0xAAAA, newChild.readHalfWord(SIOMULTI0), "carried child still receives the parent word");
        assertEquals(0xBBBB, host.serial.readHalfWord(SIOMULTI1), "host still sees the carried child's word");
        assertTrue(serialIrq(newChildIrq), "carried child is interrupted by the parent transfer");
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
