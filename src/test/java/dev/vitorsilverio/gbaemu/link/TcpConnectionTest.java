package dev.vitorsilverio.gbaemu.link;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end check of the real NIO transport over a loopback TCP connection: host + join
/// connect, then a frame round-trips. Complements {@link GbaLinkTest}, which exercises the
/// protocol over the in-memory transport (bypassing sockets).
class TcpConnectionTest {

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /// Pumps both endpoints (forcing a poll regardless of the poll-mode interval) until the
    /// condition holds or the budget runs out.
    private static boolean pump(TcpConnection a, TcpConnection b, java.util.function.BooleanSupplier done)
            throws InterruptedException {
        for (int outer = 0; outer < 300; outer++) {
            for (int inner = 0; inner < LinkPollMode.TRANSFER.interval(); inner++) {
                a.tick();
                b.tick();
            }
            if (done.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2);
        }
        return done.getAsBoolean();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void hostAndJoinConnectAndRoundTripAFrame() throws IOException, InterruptedException {
        int port = freePort();
        TcpConnection host = new TcpConnection();
        TcpConnection client = new TcpConnection();
        host.setLinkPollMode(LinkPollMode.TRANSFER);
        client.setLinkPollMode(LinkPollMode.TRANSFER);
        AtomicReference<byte[]> hostReceived = new AtomicReference<>();
        AtomicReference<byte[]> clientReceived = new AtomicReference<>();
        host.setListener((peer, data) -> hostReceived.set(data));
        client.setListener((peer, data) -> clientReceived.set(data));
        try {
            host.hostTcp("127.0.0.1", port);
            client.joinTcp("127.0.0.1", port);

            assertTrue(pump(host, client, () -> host.isConnected() && client.isConnected()),
                    "host and client should connect over loopback");

            client.send(new byte[]{0x01, 0x02, 0x03, 0x04});
            assertTrue(pump(host, client, () -> hostReceived.get() != null), "host should receive the frame");
            assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, hostReceived.get());

            host.send(new byte[]{0x0A, 0x0B});
            assertTrue(pump(host, client, () -> clientReceived.get() != null), "client should receive the reply");
            assertArrayEquals(new byte[]{0x0A, 0x0B}, clientReceived.get());
        } finally {
            host.disconnect();
            client.disconnect();
        }
    }
}
