package dev.vitorsilverio.gbaemu.link;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;

/// Non-blocking NIO transport between two emulator instances over TCP (or a local Unix-domain
/// socket). Ported from gbcemu's Multiplayer, trimmed of the GB-specific bookkeeping: it just
/// ships opaque byte frames. {@link #tick()} accepts/finishes connections and drains I/O; it
/// is gated by {@link LinkPollMode} so it does not hammer the socket every cycle.
public final class TcpConnection implements LinkPollingConnection {

    private static final int MAX_DRAIN_READS_PER_CALL = 32;
    private static final int MAX_DRAIN_WRITES_PER_CALL = 32;
    private static final int RECEIVE_BUFFER_CAPACITY = 4096;

    private boolean connected;
    private boolean hosting;
    private boolean connecting;
    private SocketChannel channel;
    private ServerSocketChannel serverChannel;
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(RECEIVE_BUFFER_CAPACITY);
    private final ArrayDeque<ByteBuffer> outboundFrames = new ArrayDeque<>();
    private SocketAddress address;
    private ProtocolFamily protocolFamily;
    private PhysicalConnectionListener listener;
    private int ticks;
    private LinkPollMode pollMode = LinkPollMode.IDLE;
    private String status = "Disconnected";

    @Override
    public void setListener(PhysicalConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void setLinkPollMode(LinkPollMode mode) {
        pollMode = mode == null ? LinkPollMode.IDLE : mode;
    }

    @Override
    public void hostTcp(String host, int port) {
        protocolFamily = StandardProtocolFamily.INET;
        address = new InetSocketAddress(host, port);
        host();
    }

    @Override
    public void joinTcp(String host, int port) {
        protocolFamily = StandardProtocolFamily.INET;
        address = new InetSocketAddress(host, port);
        join();
    }

    @Override
    public void hostLocal(String path) {
        protocolFamily = StandardProtocolFamily.UNIX;
        Path socketFile = Path.of(path);
        try {
            if (socketFile.toFile().exists()) {
                socketFile.toFile().delete();
            }
        } catch (RuntimeException ignored) {
        }
        address = UnixDomainSocketAddress.of(socketFile);
        host();
    }

    @Override
    public void joinLocal(String path) {
        protocolFamily = StandardProtocolFamily.UNIX;
        address = UnixDomainSocketAddress.of(Path.of(path));
        join();
    }

    private void host() {
        disconnect();
        try {
            serverChannel = ServerSocketChannel.open(protocolFamily);
            serverChannel.configureBlocking(false);
            serverChannel.bind(address);
            hosting = true;
            status = "Hosting on " + address;
        } catch (IOException e) {
            status = "Failed to host: " + e.getMessage();
            throw new RuntimeException("Failed to host link", e);
        }
    }

    private void join() {
        disconnect();
        try {
            channel = SocketChannel.open(protocolFamily);
            configureSocket(channel);
            connected = channel.connect(address);
            hosting = false;
            connecting = !connected;
            status = connected ? "Connected to " + address : "Connecting to " + address;
        } catch (IOException e) {
            connected = false;
            connecting = false;
            status = "Failed to join: " + e.getMessage();
            throw new RuntimeException("Failed to join link", e);
        }
    }

    @Override
    public void tick() {
        try {
            ticks++;
            if (ticks < pollMode.interval()) {
                return;
            }
            ticks = 0;
            acceptPendingConnection();
            finishPendingConnection();
            if (!connected || listener == null) {
                return;
            }
            drainOutboundBounded();
            drainReceiveBounded();
        } catch (IOException e) {
            disconnect("Connection lost");
        }
    }

    @Override
    public void send(byte[] frame) {
        if (!connected || frame == null || frame.length == 0) {
            return;
        }
        outboundFrames.addLast(ByteBuffer.wrap(frame.clone()));
        try {
            drainOutboundBounded();
            drainReceiveBounded();
        } catch (IOException e) {
            disconnect("Connection lost");
        }
    }

    private void acceptPendingConnection() throws IOException {
        if (!hosting || serverChannel == null) {
            return;
        }
        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return;
        }
        channel = accepted;
        configureSocket(channel);
        connected = true;
        connecting = false;
        status = "Connected to " + channel.getRemoteAddress();
        serverChannel.close();
        serverChannel = null;
    }

    private void finishPendingConnection() throws IOException {
        if (!connecting || channel == null) {
            return;
        }
        if (!channel.finishConnect()) {
            return;
        }
        connected = true;
        connecting = false;
        status = "Connected to " + address;
    }

    private void configureSocket(SocketChannel socketChannel) throws IOException {
        socketChannel.configureBlocking(false);
        if (protocolFamily == StandardProtocolFamily.INET) {
            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
    }

    private void drainOutboundBounded() throws IOException {
        if (!connected || channel == null) {
            return;
        }
        for (int write = 0; write < MAX_DRAIN_WRITES_PER_CALL && !outboundFrames.isEmpty(); write++) {
            ByteBuffer frame = outboundFrames.peekFirst();
            int written = channel.write(frame);
            if (written == 0) {
                return;
            }
            if (!frame.hasRemaining()) {
                outboundFrames.removeFirst();
            }
        }
    }

    private void drainReceiveBounded() throws IOException {
        if (!connected || listener == null || channel == null) {
            return;
        }
        for (int read = 0; read < MAX_DRAIN_READS_PER_CALL; read++) {
            receiveBuffer.clear();
            int bytesRead = channel.read(receiveBuffer);
            if (bytesRead < 0) {
                disconnect("Connection closed by peer");
                return;
            }
            if (bytesRead == 0) {
                return;
            }
            byte[] packet = new byte[bytesRead];
            receiveBuffer.flip();
            receiveBuffer.get(packet);
            listener.onFrame(packet);
        }
    }

    @Override
    public void disconnect() {
        disconnect("Disconnected");
    }

    private void disconnect(String finalStatus) {
        connected = false;
        hosting = false;
        connecting = false;
        pollMode = LinkPollMode.IDLE;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        } finally {
            channel = null;
            serverChannel = null;
            outboundFrames.clear();
            status = finalStatus;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isHosting() {
        return hosting;
    }

    @Override
    public boolean isActive() {
        return connected || hosting || connecting;
    }

    @Override
    public String status() {
        return status;
    }
}
