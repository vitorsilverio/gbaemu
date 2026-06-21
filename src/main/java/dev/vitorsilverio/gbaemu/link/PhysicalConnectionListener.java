package dev.vitorsilverio.gbaemu.link;

/// Receives raw byte frames from a {@link PhysicalConnection} (the link protocol layer,
/// {@link GbaLink}, implements this). Frames are tagged with the source {@code peer} so a host
/// relaying several children can reassemble each child's byte stream independently: peer 0 is
/// the host itself (and the single peer a client talks to); children occupy slots 1..3.
public interface PhysicalConnectionListener {

    /// A chunk of bytes arrived from {@code peer}. It may be a partial frame or several frames,
    /// so the listener must do its own (per-peer) framing.
    void onFrame(int peer, byte[] frame);

    /// A new child connected on a host and was assigned slot {@code peer} (1..3). Only hosts
    /// fire this; the default is a no-op for single-peer transports.
    default void onPeerConnected(int peer) {
    }

    /// A peer dropped; its slot {@code peer} is now free. Default is a no-op.
    default void onPeerDisconnected(int peer) {
    }
}
