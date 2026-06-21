package dev.vitorsilverio.gbaemu.link;

/// A protocol-agnostic byte-frame transport between two emulator instances. Ported (trimmed)
/// from the gbcemu connection abstraction: it carries opaque {@code byte[]} frames, so the
/// same transport serves the GBA link protocol ({@link GbaLink}) as it did the Game Boy one.
/// Non-blocking — {@link #tick()} pumps I/O and {@link #send} enqueues; all called from the
/// emulation thread.
public interface PhysicalConnection extends AutoCloseable {

    void setListener(PhysicalConnectionListener listener);

    /// Sends to every connected peer (a host broadcasts to all its children).
    void send(byte[] frame);

    /// Sends to a single peer (a host targeting one child by its slot). Single-peer transports
    /// ignore {@code peer} and behave like {@link #send}.
    default void sendTo(int peer, byte[] frame) {
        send(frame);
    }

    void hostTcp(String host, int port);

    void joinTcp(String host, int port);

    void hostLocal(String path);

    void joinLocal(String path);

    void disconnect();

    void tick();

    boolean isConnected();

    boolean isHosting();

    default boolean isActive() {
        return isConnected() || isHosting();
    }

    /// Number of currently-connected peers (children on a host; 0 or 1 on a client).
    default int connectedPeers() {
        return isConnected() ? 1 : 0;
    }

    String status();

    @Override
    default void close() {
        disconnect();
    }
}
