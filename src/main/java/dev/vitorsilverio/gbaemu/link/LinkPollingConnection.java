package dev.vitorsilverio.gbaemu.link;

/// A {@link PhysicalConnection} whose poll cadence can be tuned by the link layer.
public interface LinkPollingConnection extends PhysicalConnection {

    void setLinkPollMode(LinkPollMode mode);
}
