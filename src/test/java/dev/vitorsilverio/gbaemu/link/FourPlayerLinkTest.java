package dev.vitorsilverio.gbaemu.link;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.serial.GbaSerial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Up to four serial peripherals wired through an in-memory star: the host (id 0) relays three
/// children (ids 1..3). Exercises the host-assigned-id handshake (the {@code ASSIGN} frame) and
/// the full 4-slot exchange over {@link GbaLink} without real sockets — complements the
/// 2-player {@link GbaLinkTest}.
class FourPlayerLinkTest {

    private static final int SIOMULTI0 = 0x04000120;
    private static final int SIOMULTI1 = 0x04000122;
    private static final int SIOMULTI2 = 0x04000124;
    private static final int SIOMULTI3 = 0x04000126;
    private static final int SIOCNT = 0x04000128;
    private static final int SIOMLT_SEND = 0x0400012A;
    private static final int RCNT = 0x04000134;

    private static final int MODE_MULTIPLAYER = 2 << 12;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;
    private static final int ABSENT = 0xFFFF;

    private record Unit(GbaSerial serial, GbaInterruptController interrupts) {
    }

    private static Unit unit(PhysicalConnection connection) {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaSerial serial = new GbaSerial(interrupts);
        serial.link().useConnection(connection);
        serial.writeHalfWord(RCNT, 0); // SIO mode group
        return new Unit(serial, interrupts);
    }

    private static boolean serialIrq(GbaInterruptController interrupts) {
        return (interrupts.readHalfWord(GbaInterruptController.IF) & GbaInterrupt.SERIAL.mask()) != 0;
    }

    /// Publishes a unit's outgoing word and arms its multiplayer transfer (a child then waits
    /// for the parent to drive it; the parent kicks the exchange off itself).
    private static void publishAndArm(Unit unit, int word) {
        unit.serial.writeHalfWord(SIOMLT_SEND, word);
        unit.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
    }

    private static int idOf(Unit unit) {
        return (unit.serial.readHalfWord(SIOCNT) >>> 4) & 0x3;
    }

    @Test
    void hostAndThreeChildrenExchangeAllFourSlots() {
        InMemoryStar star = new InMemoryStar();
        Unit host = unit(star.host());
        Unit child1 = unit(star.addChild()); // ASSIGN id 1
        Unit child2 = unit(star.addChild()); // ASSIGN id 2
        Unit child3 = unit(star.addChild()); // ASSIGN id 3

        publishAndArm(child1, 0xB111);
        publishAndArm(child2, 0xC222);
        publishAndArm(child3, 0xD333);

        // Parent publishes its word and starts; ticking completes the transfer and broadcasts.
        host.serial.writeHalfWord(SIOMLT_SEND, 0xA000);
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
        host.serial.tick(10_000);

        for (Unit unit : new Unit[]{host, child1, child2, child3}) {
            assertEquals(0xA000, unit.serial.readHalfWord(SIOMULTI0));
            assertEquals(0xB111, unit.serial.readHalfWord(SIOMULTI1));
            assertEquals(0xC222, unit.serial.readHalfWord(SIOMULTI2));
            assertEquals(0xD333, unit.serial.readHalfWord(SIOMULTI3));
            assertEquals(0, unit.serial.readHalfWord(SIOCNT) & START, "start/busy clears on completion");
            assertTrue(serialIrq(unit.interrupts), "every unit raises the serial IRQ");
        }
        assertEquals(0, idOf(host), "host is id 0");
        assertEquals(1, idOf(child1), "child1 is id 1");
        assertEquals(2, idOf(child2), "child2 is id 2");
        assertEquals(3, idOf(child3), "child3 is id 3");
    }

    @Test
    void aDroppedChildLeavesItsSlotAbsentAndOthersContinue() {
        InMemoryStar star = new InMemoryStar();
        Unit host = unit(star.host());
        Unit child1 = unit(star.addChild());
        unit(star.addChild()); // child 2: publishes, then drops before the exchange

        publishAndArm(child1, 0xB111);
        // child 2 publishes a word, then disconnects before the parent drives the exchange.
        star.disconnectChild(2);

        host.serial.writeHalfWord(SIOMLT_SEND, 0xA000);
        host.serial.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);
        host.serial.tick(10_000);

        assertEquals(0xA000, host.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xB111, host.serial.readHalfWord(SIOMULTI1), "surviving child is still present");
        assertEquals(ABSENT, host.serial.readHalfWord(SIOMULTI2), "dropped child's slot reads 0xFFFF");
        assertTrue(serialIrq(host.interrupts));

        // The surviving child received the broadcast and completed too.
        assertEquals(0xA000, child1.serial.readHalfWord(SIOMULTI0));
        assertEquals(0xB111, child1.serial.readHalfWord(SIOMULTI1));
        assertTrue(serialIrq(child1.interrupts));
    }
}
