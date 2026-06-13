package dev.vitorsilverio.gbaemu.link;

/// Callbacks from {@link GbaLink} to the serial peripheral when a networked transfer resolves
/// or the link drops. Implemented by {@code GbaSerial}.
public interface SerialLinkListener {

    /// A multiplayer-mode exchange completed: {@code words[0..3]} are the data from players 0-3
    /// (absent players are {@code 0xFFFF}). The peripheral latches these into SIOMULTI0-3.
    void onMultiplayerResult(int[] words);

    /// The link dropped while a transfer was pending; the peripheral should fail it gracefully.
    void onLinkDisconnected();
}
