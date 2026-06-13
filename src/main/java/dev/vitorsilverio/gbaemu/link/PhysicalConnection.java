package dev.vitorsilverio.gbaemu.link;

/// A protocol-agnostic byte-frame transport between two emulator instances. Ported (trimmed)
/// from the gbcemu connection abstraction: it carries opaque {@code byte[]} frames, so the
/// same transport serves the GBA link protocol ({@link GbaLink}) as it did the Game Boy one.
/// Non-blocking — {@link #tick()} pumps I/O and {@link #send} enqueues; all called from the
/// emulation thread.
public interface PhysicalConnection extends AutoCloseable {

    void setListener(PhysicalConnectionListener listener);

    void send(byte[] frame);

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

    String status();

    @Override
    default void close() {
        disconnect();
    }
}
