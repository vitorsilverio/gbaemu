package dev.vitorsilverio.gbaemu.link;

/// Callbacks from {@link GbaLink} to the serial peripheral when a networked transfer resolves
/// or the link drops. Implemented by {@code GbaSerial}.
public interface SerialLinkListener {

    /// A multiplayer-mode exchange completed: {@code words[0..3]} are the data from players 0-3
    /// (absent players are {@code 0xFFFF}). The peripheral latches these into SIOMULTI0-3.
    void onMultiplayerResult(int[] words);

    /// The peer, acting as the Normal-mode master, is driving a transfer (this unit is the slave):
    /// {@code data} is the master's outgoing word. The peripheral answers with its own word and
    /// latches {@code data} into SIODATA.
    void onNormalRequest(int data);

    /// The peer (this unit's Normal-mode slave) answered the transfer this unit drove as master:
    /// {@code data} is the slave's word, latched into SIODATA to complete the transfer.
    void onNormalResponse(int data);

    /// The link dropped while a transfer was pending; the peripheral should fail it gracefully.
    void onLinkDisconnected();
}
