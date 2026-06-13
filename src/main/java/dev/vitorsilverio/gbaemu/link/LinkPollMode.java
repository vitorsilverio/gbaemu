package dev.vitorsilverio.gbaemu.link;

/// How often the transport polls the socket, expressed as a number of hardware ticks between
/// polls. Idle far less often than when connected. Ported from gbcemu.
public enum LinkPollMode {
    IDLE(2048),
    CONNECTED(128),
    TRANSFER(8);

    private final int interval;

    LinkPollMode(int interval) {
        this.interval = interval;
    }

    public int interval() {
        return interval;
    }
}
