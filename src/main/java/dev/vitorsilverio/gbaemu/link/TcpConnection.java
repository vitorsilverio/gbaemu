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

/// Non-blocking NIO transport between emulator instances over TCP (or a local Unix-domain
/// socket). A host keeps its listening socket open and relays a **star** of up to three children
/// (slots 1..3): {@link #send} broadcasts to all of them, {@link #sendTo} targets one, and reads
/// are delivered to the listener tagged with the originating child slot. A client has a single
/// peer (slot 0, the host). Ported and extended from gbcemu's Multiplayer; it ships opaque byte
/// frames. {@link #tick()} accepts/finishes connections and drains I/O, gated by
/// {@link LinkPollMode} so it does not hammer the sockets every cycle. A read/write error on one
/// child drops only that child; the host keeps running.
public final class TcpConnection implements LinkPollingConnection {

    private static final int MAX_DRAIN_READS_PER_CALL = 32;
    private static final int MAX_DRAIN_WRITES_PER_CALL = 32;
    private static final int RECEIVE_BUFFER_CAPACITY = 4096;
    private static final int MAX_SLOTS = 4;       // [0] = client's link to the host; [1..3] = host's children
    private static final int FIRST_CHILD_SLOT = 1;

    private boolean hosting;
    private boolean connecting;                   // client connect in progress (peers[0] not yet usable)
    private ServerSocketChannel serverChannel;
    private final Peer[] peers = new Peer[MAX_SLOTS];
    private SocketAddress address;
    private ProtocolFamily protocolFamily;
    private PhysicalConnectionListener listener;
    private int ticks;
    private LinkPollMode pollMode = LinkPollMode.IDLE;
    private String status = "Disconnected";

    /// One connected socket plus its private receive buffer and outbound queue.
    private static final class Peer {
        final SocketChannel channel;
        final ByteBuffer receiveBuffer = ByteBuffer.allocate(RECEIVE_BUFFER_CAPACITY);
        final ArrayDeque<ByteBuffer> outbound = new ArrayDeque<>();

        Peer(SocketChannel channel) {
            this.channel = channel;
        }
    }

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
            status = hostStatus();
        } catch (IOException e) {
            status = "Failed to host: " + e.getMessage();
            throw new RuntimeException("Failed to host link", e);
        }
    }

    private void join() {
        disconnect();
        try {
            SocketChannel channel = SocketChannel.open(protocolFamily);
            configureSocket(channel);
            boolean done = channel.connect(address);
            peers[0] = new Peer(channel);
            hosting = false;
            connecting = !done;
            status = done ? "Connected to " + address : "Connecting to " + address;
        } catch (IOException e) {
            connecting = false;
            status = "Failed to join: " + e.getMessage();
            throw new RuntimeException("Failed to join link", e);
        }
    }

    @Override
    public void tick() {
        ticks++;
        if (ticks < pollMode.interval()) {
            return;
        }
        ticks = 0;
        try {
            acceptPendingConnections();
            finishPendingConnection();
        } catch (IOException e) {
            // A failure on the listening/connecting socket is fatal to the whole session.
            disconnect("Connection lost");
            return;
        }
        drainOutboundAll();
        if (listener != null) {
            drainReceiveAll();
        }
    }

    @Override
    public void send(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        if (hosting) {
            for (int slot = FIRST_CHILD_SLOT; slot < peers.length; slot++) {
                enqueue(slot, frame);
            }
        } else {
            enqueue(0, frame);
        }
        pump();
    }

    @Override
    public void sendTo(int peer, byte[] frame) {
        if (frame == null || frame.length == 0 || peer < 0 || peer >= peers.length) {
            return;
        }
        enqueue(peer, frame);
        pump();
    }

    private void enqueue(int slot, byte[] frame) {
        Peer peer = peers[slot];
        if (peer != null) {
            peer.outbound.addLast(ByteBuffer.wrap(frame.clone()));
        }
    }

    private void pump() {
        drainOutboundAll();
        if (listener != null) {
            drainReceiveAll();
        }
    }

    private void acceptPendingConnections() throws IOException {
        if (!hosting || serverChannel == null) {
            return;
        }
        SocketChannel accepted;
        while ((accepted = serverChannel.accept()) != null) {
            int slot = freeChildSlot();
            if (slot < 0) {
                accepted.close(); // host is full (already 3 children)
                continue;
            }
            configureSocket(accepted);
            peers[slot] = new Peer(accepted);
            status = hostStatus();
            if (listener != null) {
                listener.onPeerConnected(slot);
            }
        }
    }

    private void finishPendingConnection() throws IOException {
        if (!connecting || peers[0] == null) {
            return;
        }
        if (!peers[0].channel.finishConnect()) {
            return;
        }
        connecting = false;
        status = "Connected to " + address;
    }

    private void configureSocket(SocketChannel socketChannel) throws IOException {
        socketChannel.configureBlocking(false);
        if (protocolFamily == StandardProtocolFamily.INET) {
            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
    }

    private void drainOutboundAll() {
        for (int slot = 0; slot < peers.length; slot++) {
            Peer peer = peers[slot];
            if (peer == null) {
                continue;
            }
            try {
                drainOutbound(peer);
            } catch (IOException e) {
                dropPeer(slot, "Connection lost");
            }
        }
    }

    private void drainOutbound(Peer peer) throws IOException {
        if (!peer.channel.isConnected()) {
            return; // a client still finishing connect: nothing to write yet
        }
        for (int write = 0; write < MAX_DRAIN_WRITES_PER_CALL && !peer.outbound.isEmpty(); write++) {
            ByteBuffer frame = peer.outbound.peekFirst();
            int written = peer.channel.write(frame);
            if (written == 0) {
                return;
            }
            if (!frame.hasRemaining()) {
                peer.outbound.removeFirst();
            }
        }
    }

    private void drainReceiveAll() {
        for (int slot = 0; slot < peers.length; slot++) {
            Peer peer = peers[slot];
            if (peer == null) {
                continue;
            }
            try {
                drainReceive(slot, peer);
            } catch (IOException e) {
                dropPeer(slot, "Connection lost");
            }
        }
    }

    private void drainReceive(int slot, Peer peer) throws IOException {
        if (!peer.channel.isConnected()) {
            return;
        }
        for (int read = 0; read < MAX_DRAIN_READS_PER_CALL; read++) {
            peer.receiveBuffer.clear();
            int bytesRead = peer.channel.read(peer.receiveBuffer);
            if (bytesRead < 0) {
                dropPeer(slot, slot == 0 ? "Connection closed by host" : "Child disconnected");
                return;
            }
            if (bytesRead == 0) {
                return;
            }
            byte[] packet = new byte[bytesRead];
            peer.receiveBuffer.flip();
            peer.receiveBuffer.get(packet);
            if (listener != null) {
                listener.onFrame(slot, packet);
            }
        }
    }

    private int freeChildSlot() {
        for (int slot = FIRST_CHILD_SLOT; slot < peers.length; slot++) {
            if (peers[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    private int connectedChildren() {
        int count = 0;
        for (int slot = FIRST_CHILD_SLOT; slot < peers.length; slot++) {
            if (peers[slot] != null) {
                count++;
            }
        }
        return count;
    }

    private String hostStatus() {
        int children = connectedChildren();
        return children == 0
                ? "Hosting on " + address + " (waiting)"
                : "Hosting on " + address + " (" + (children + 1) + " players)";
    }

    private void dropPeer(int slot, String reason) {
        Peer peer = peers[slot];
        if (peer == null) {
            return;
        }
        try {
            if (peer.channel.isOpen()) {
                peer.channel.close();
            }
        } catch (IOException ignored) {
        }
        peers[slot] = null;
        if (listener != null) {
            listener.onPeerDisconnected(slot);
        }
        if (hosting) {
            status = hostStatus();
        } else {
            connecting = false;
            status = reason;
        }
    }

    @Override
    public void disconnect() {
        disconnect("Disconnected");
    }

    private void disconnect(String finalStatus) {
        hosting = false;
        connecting = false;
        pollMode = LinkPollMode.IDLE;
        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        serverChannel = null;
        for (int slot = 0; slot < peers.length; slot++) {
            Peer peer = peers[slot];
            if (peer == null) {
                continue;
            }
            try {
                if (peer.channel.isOpen()) {
                    peer.channel.close();
                }
            } catch (IOException ignored) {
            }
            peers[slot] = null;
        }
        status = finalStatus;
    }

    @Override
    public boolean isConnected() {
        if (hosting) {
            return connectedChildren() > 0;
        }
        return !connecting && peers[0] != null;
    }

    @Override
    public boolean isHosting() {
        return hosting;
    }

    @Override
    public boolean isActive() {
        return hosting || connecting || isConnected();
    }

    @Override
    public int connectedPeers() {
        return hosting ? connectedChildren() : (isConnected() ? 1 : 0);
    }

    @Override
    public String status() {
        return status;
    }
}
