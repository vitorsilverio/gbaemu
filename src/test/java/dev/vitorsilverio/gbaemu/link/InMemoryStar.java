package dev.vitorsilverio.gbaemu.link;

import java.util.LinkedHashMap;
import java.util.Map;

/// A synchronous in-memory star for tests: one host plus up to three children (ids 1..3), with
/// no sockets. Unlike {@link InMemoryConnectionPair} it models the host relay and fires
/// {@code onPeerConnected} the moment a child wires up its listener, so the full host-assigned-id
/// handshake (the {@code ASSIGN} frame) is exercised. Delivery is immediate inside {@code send}:
/// a child's frame reaches the host tagged with that child's id; the host's broadcast reaches
/// every child tagged as peer 0 (the host).
public final class InMemoryStar {

    private final HostEndpoint host = new HostEndpoint();

    public PhysicalConnection host() {
        return host;
    }

    /// Wires a new child into the lowest free slot (1..3) and returns its endpoint. The host's
    /// {@code onPeerConnected} fires later, when the child attaches its listener.
    public PhysicalConnection addChild() {
        int id = -1;
        for (int slot = 1; slot <= 3; slot++) {
            if (!host.children.containsKey(slot)) {
                id = slot;
                break;
            }
        }
        if (id < 0) {
            throw new IllegalStateException("host is full (3 children)");
        }
        ChildEndpoint child = new ChildEndpoint(id);
        child.host = host;
        host.children.put(id, child);
        return child;
    }

    /// Drops a child and notifies the host (mirrors a socket close).
    public void disconnectChild(int id) {
        ChildEndpoint child = host.children.remove(id);
        if (child != null) {
            child.host = null;
            if (host.listener != null) {
                host.listener.onPeerDisconnected(id);
            }
        }
    }

    private static final class HostEndpoint implements PhysicalConnection {
        private final Map<Integer, ChildEndpoint> children = new LinkedHashMap<>();
        private PhysicalConnectionListener listener;

        @Override
        public void setListener(PhysicalConnectionListener listener) {
            this.listener = listener;
        }

        @Override
        public void send(byte[] frame) {
            if (frame == null || frame.length == 0) {
                return;
            }
            for (ChildEndpoint child : children.values()) {
                if (child.listener != null) {
                    child.listener.onFrame(0, frame.clone());
                }
            }
        }

        @Override
        public void sendTo(int peer, byte[] frame) {
            ChildEndpoint child = children.get(peer);
            if (child != null && child.listener != null && frame != null && frame.length > 0) {
                child.listener.onFrame(0, frame.clone());
            }
        }

        @Override
        public void disconnect() {
            children.clear();
        }

        @Override
        public boolean isConnected() {
            return !children.isEmpty();
        }

        @Override
        public boolean isHosting() {
            return true;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public int connectedPeers() {
            return children.size();
        }

        @Override
        public String status() {
            return "Hosting in memory (" + children.size() + " children)";
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
        public void tick() {
        }
    }

    private static final class ChildEndpoint implements PhysicalConnection {
        private final int id;
        private HostEndpoint host;
        private PhysicalConnectionListener listener;

        private ChildEndpoint(int id) {
            this.id = id;
        }

        @Override
        public void setListener(PhysicalConnectionListener listener) {
            this.listener = listener;
            // Attaching the listener is the moment both ends are ready: tell the host so it
            // accepts us and sends our ASSIGN (just like a real socket accept).
            if (host != null && host.listener != null) {
                host.listener.onPeerConnected(id);
            }
        }

        @Override
        public void send(byte[] frame) {
            if (host != null && host.listener != null && frame != null && frame.length > 0) {
                host.listener.onFrame(id, frame.clone());
            }
        }

        @Override
        public void sendTo(int peer, byte[] frame) {
            send(frame);
        }

        @Override
        public void disconnect() {
            host = null;
        }

        @Override
        public boolean isConnected() {
            return host != null;
        }

        @Override
        public boolean isHosting() {
            return false;
        }

        @Override
        public boolean isActive() {
            return isConnected();
        }

        @Override
        public String status() {
            return isConnected() ? "Child " + id + " in memory" : "Disconnected";
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
        public void tick() {
        }
    }
}
