package dev.vitorsilverio.gbaemu.link;

/// The idle placeholder transport held by {@link GbaLink} before hosting/joining. Hosting or
/// joining replaces it with a real {@link TcpConnection}, so the host/join methods here are
/// never expected to run.
public final class DisconnectedPhysicalConnection implements PhysicalConnection {

    @Override
    public void setListener(PhysicalConnectionListener listener) {
    }

    @Override
    public void send(byte[] frame) {
    }

    @Override
    public void hostTcp(String host, int port) {
        throw new UnsupportedOperationException("not connected");
    }

    @Override
    public void joinTcp(String host, int port) {
        throw new UnsupportedOperationException("not connected");
    }

    @Override
    public void hostLocal(String path) {
        throw new UnsupportedOperationException("not connected");
    }

    @Override
    public void joinLocal(String path) {
        throw new UnsupportedOperationException("not connected");
    }

    @Override
    public void disconnect() {
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isHosting() {
        return false;
    }

    @Override
    public String status() {
        return "Disconnected";
    }
}
