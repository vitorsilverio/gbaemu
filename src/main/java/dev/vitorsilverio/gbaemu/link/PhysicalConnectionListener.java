package dev.vitorsilverio.gbaemu.link;

/// Receives raw byte frames from a {@link PhysicalConnection} (the link protocol layer,
/// {@link GbaLink}, implements this).
@FunctionalInterface
public interface PhysicalConnectionListener {
    void onFrame(byte[] frame);
}
