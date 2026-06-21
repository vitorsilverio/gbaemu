package dev.vitorsilverio.gbaemu.link;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.serial.GbaSerial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end over a real loopback TCP link (not the in-memory transport): a host and a guest
/// {@link GbaSerial} connect, then the host drives a multiplayer transfer. Reproduces the GUI's
/// emulation-thread path, where {@code serial.tick()} pumps the link. The {@link Timeout} turns a
/// hang ("host freezes when the guest connects") into a test failure instead of hanging the run.
class SerialLinkLoopbackTest {

    private static final int SIOMULTI0 = 0x04000120;
    private static final int SIOMULTI1 = 0x04000122;
    private static final int SIOCNT = 0x04000128;
    private static final int SIOMLT_SEND = 0x0400012A;
    private static final int RCNT = 0x04000134;

    private static final int MODE_MULTIPLAYER = 2 << 12;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static boolean serialIrq(GbaInterruptController interrupts) {
        return (interrupts.readHalfWord(GbaInterruptController.IF) & GbaInterrupt.SERIAL.mask()) != 0;
    }

    /// Ticks both serials (which pump their links) until {@code done} holds or the budget runs out.
    /// 200 inner ticks per round clears the CONNECTED poll interval (128) so polling actually fires.
    private static boolean pump(GbaSerial a, GbaSerial b, BooleanSupplier done) throws InterruptedException {
        for (int round = 0; round < 1500; round++) {
            for (int i = 0; i < 200; i++) {
                a.tick(64);
                b.tick(64);
            }
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1);
        }
        return done.getAsBoolean();
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void hostSurvivesGuestConnectThenDrivesAReactiveGuest() throws IOException, InterruptedException {
        int port = freePort();
        GbaInterruptController hostIrq = new GbaInterruptController();
        GbaSerial host = new GbaSerial(hostIrq);
        GbaInterruptController guestIrq = new GbaInterruptController();
        GbaSerial guest = new GbaSerial(guestIrq);
        host.writeHalfWord(RCNT, 0);
        guest.writeHalfWord(RCNT, 0);
        try {
            host.link().hostTcp("127.0.0.1", port);
            guest.link().joinTcp("127.0.0.1", port);

            assertTrue(pump(host, guest, () -> host.link().isConnected() && guest.link().isConnected()),
                    "host and guest should connect over loopback without the host hanging");

            // Guest is reactive: multiplayer mode + IRQ enabled, publishes a word, no start bit.
            guest.writeHalfWord(SIOMLT_SEND, 0xBBBB);
            guest.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE);
            // Let the guest's word reach the host before it assembles the transfer.
            pump(host, guest, () -> host.readHalfWord(SIOMULTI1) == 0xBBBB);

            // Host drives a multiplayer transfer.
            host.writeHalfWord(SIOMLT_SEND, 0xAAAA);
            host.writeHalfWord(SIOCNT, MODE_MULTIPLAYER | IRQ_ENABLE | START);

            assertTrue(pump(host, guest, () -> (host.readHalfWord(SIOCNT) & START) == 0),
                    "the host transfer must complete (start/busy clears)");
            assertTrue(pump(host, guest, () -> serialIrq(guestIrq)),
                    "the guest must be interrupted by the parent-driven transfer");
            assertEquals(0xAAAA, guest.readHalfWord(SIOMULTI0), "guest sees the parent word");
            assertTrue(serialIrq(hostIrq), "the host completes its own transfer with an IRQ");
        } finally {
            host.link().disconnect();
            guest.link().disconnect();
        }
    }
}
