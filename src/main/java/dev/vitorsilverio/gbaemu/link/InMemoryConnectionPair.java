package dev.vitorsilverio.gbaemu.link;

/// Two directly-wired {@link PhysicalConnection}s for tests: a {@code send} on one delivers
/// synchronously to the other's listener, with no sockets. {@code left} is the host (parent),
/// {@code right} the client (child). Ported from gbcemu's in-memory pair.
public final class InMemoryConnectionPair {

    private final Endpoint left = new Endpoint(true);
    private final Endpoint right = new Endpoint(false);

    public InMemoryConnectionPair() {
        left.peer = right;
        right.peer = left;
    }

    public PhysicalConnection left() {
        return left;
    }

    public PhysicalConnection right() {
        return right;
    }

    private static final class Endpoint implements PhysicalConnection {
        private final boolean hosting;
        private Endpoint peer;
        private PhysicalConnectionListener listener;

        private Endpoint(boolean hosting) {
            this.hosting = hosting;
        }

        @Override
        public void setListener(PhysicalConnectionListener listener) {
            this.listener = listener;
        }

        @Override
        public void send(byte[] frame) {
            if (peer != null && peer.listener != null && frame != null && frame.length > 0) {
                peer.listener.onFrame(frame.clone());
            }
        }

        @Override
        public void hostTcp(String host, int port) {
        }

        @Override
        public void joinTcp(String host, int port) {
        }

        @Override
        public void hostLocal(String path) {
        }

        @Override
        public void joinLocal(String path) {
        }

        @Override
        public void disconnect() {
            Endpoint oldPeer = peer;
            peer = null;
            if (oldPeer != null && oldPeer.peer == this) {
                oldPeer.peer = null;
            }
        }

        @Override
        public void tick() {
            // Synchronous delivery in send(); nothing to pump.
        }

        @Override
        public boolean isConnected() {
            return peer != null;
        }

        @Override
        public boolean isHosting() {
            return hosting;
        }

        @Override
        public boolean isActive() {
            return isConnected();
        }

        @Override
        public String status() {
            return isConnected() ? "Connected in memory" : "Disconnected";
        }
    }
}
