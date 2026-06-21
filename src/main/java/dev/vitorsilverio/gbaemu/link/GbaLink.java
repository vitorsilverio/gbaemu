package dev.vitorsilverio.gbaemu.link;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/// Brokers the GBA SIO **multiplayer-mode** exchange over a {@link PhysicalConnection}. Unlike
/// the Game Boy's byte-at-a-time master/slave clocking, multiplayer mode is a framed exchange:
/// the parent (host, id 0) starts a transfer, every unit contributes one 16-bit word, and all
/// units receive all words (absent slots read {@code 0xFFFF}) — which is why it is far less
/// cycle-sync sensitive than the GB link.
///
/// Topology is a **star with the host relaying** (just like the real cable): up to three children
/// (ids 1..3) connect to one host (id 0); children never talk to each other. The host assigns
/// each child its id on connect (an {@code ASSIGN} frame), caches each child's latest word, and
/// on a transfer assembles SIOMULTI0-3 and broadcasts the result to everyone.
///
/// Wire protocol (tiny fixed frames):
/// <ul>
///   <li>{@code WORD}   = {@code 0x01, senderId, lo, hi} — a unit's latest SIOMLT_SEND. The host
///       places it by the <em>source slot</em> the bytes arrived on, not the embedded id.</li>
///   <li>{@code RESULT} = {@code 0x02, w0lo,w0hi, w1lo,w1hi, w2lo,w2hi, w3lo,w3hi} — the parent's
///       assembled SIOMULTI0-3, broadcast on transfer completion.</li>
///   <li>{@code ASSIGN} = {@code 0x03, id} — host → a single child, telling it its multiplayer id.</li>
/// </ul>
///
/// All methods run on the emulation thread (the peripheral writes, the tick, and the socket
/// drain are all driven from there); {@code connection} is volatile only because the UI reads
/// {@link #status()} / {@link #isConnected()} from the AWT thread.
public final class GbaLink implements PhysicalConnectionListener {

    private static final int FRAME_WORD = 0x01;
    private static final int FRAME_RESULT = 0x02;
    private static final int FRAME_ASSIGN = 0x03;
    // Normal-mode (point-to-point) exchange: the master drives a request, the slave answers.
    private static final int FRAME_NORMAL_REQ = 0x04; // {0x04, b0,b1,b2,b3} — master's word
    private static final int FRAME_NORMAL_RSP = 0x05; // {0x05, b0,b1,b2,b3} — slave's word
    private static final int ABSENT = 0xFFFF;
    private static final int MAX_PLAYERS = 4;

    private volatile PhysicalConnection connection = new DisconnectedPhysicalConnection();
    private SerialLinkListener serial;
    private final int[] inWords = newAbsentWords();
    // One reassembly buffer per source peer (0 = host/self-link, 1..3 = children) so interleaved
    // partial TCP frames from several children never corrupt each other.
    private final ByteArrayOutputStream[] rx = newRxBuffers();
    // This child's host-assigned id; -1 until an ASSIGN arrives (then localId() falls back to the
    // implicit parent=0 / child=1, which keeps the 2-player in-memory path working without a host).
    private int assignedId = -1;
    private boolean wasConnected;

    public void attachSerial(SerialLinkListener serial) {
        this.serial = serial;
    }

    public void hostTcp(String host, int port) {
        switchConnection(new TcpConnection());
        connection.hostTcp(host, port);
    }

    public void joinTcp(String host, int port) {
        switchConnection(new TcpConnection());
        connection.joinTcp(host, port);
    }

    public void hostLocal(String path) {
        switchConnection(new TcpConnection());
        connection.hostLocal(path);
    }

    public void joinLocal(String path) {
        switchConnection(new TcpConnection());
        connection.joinLocal(path);
    }

    /// Replaces the live transport (used by tests to inject an in-memory endpoint, and by the
    /// desktop app to adopt a connection carried across a console restart).
    public void useConnection(PhysicalConnection newConnection) {
        switchConnection(newConnection);
    }

    /// The live transport. Exposed so the desktop app can carry a connected session across a
    /// console restart — the GBA "cable" stays plugged in while a game reboots from the BIOS,
    /// which is how link games (e.g. Mario Kart) expect extra players to join: connected first,
    /// then booting into the session.
    public PhysicalConnection connection() {
        return connection;
    }

    public void disconnect() {
        connection.disconnect();
        switchConnection(new DisconnectedPhysicalConnection());
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    public boolean isHosting() {
        return connection.isHosting();
    }

    public boolean isActive() {
        return connection.isActive();
    }

    public String status() {
        return connection.status();
    }

    /// This unit's multiplayer id: the host-assigned id once known, else the implicit parent
    /// (id 0 when hosting) / single child (id 1) used by the cable-2P path.
    public int localId() {
        if (assignedId >= 0) {
            return assignedId;
        }
        return connection.isHosting() ? 0 : 1;
    }

    /// Publishes this unit's outgoing word upstream so the parent can include it in the next
    /// exchange. Only children send this; the parent's own word is delivered in the RESULT it
    /// assembles, so a host sending a WORD would just be redundant traffic.
    public void sendWord(int word) {
        if (connection.isConnected() && !connection.isHosting()) {
            connection.send(new byte[]{(byte) FRAME_WORD, (byte) localId(), (byte) word, (byte) (word >>> 8)});
        }
    }

    /// Normal-mode master → slave: drives a transfer carrying this unit's outgoing word.
    public void sendNormalRequest(int data) {
        sendNormalFrame(FRAME_NORMAL_REQ, data);
    }

    /// Normal-mode slave → master: answers a driven transfer with this unit's outgoing word.
    public void sendNormalResponse(int data) {
        sendNormalFrame(FRAME_NORMAL_RSP, data);
    }

    private void sendNormalFrame(int type, int data) {
        if (connection.isConnected()) {
            connection.send(new byte[]{
                    (byte) type, (byte) data, (byte) (data >>> 8), (byte) (data >>> 16), (byte) (data >>> 24)});
        }
    }

    /// Parent-only: assembles SIOMULTI0-3 from this unit's word (slot 0) plus the cached child
    /// words, broadcasts the result to every child, and returns it so the parent latches it too.
    /// Returns {@code null} when not connected or not the parent (the caller then runs the
    /// transfer solo / waits for a broadcast).
    public int[] startExchange(int parentWord) {
        if (!connection.isConnected() || !connection.isHosting()) {
            return null;
        }
        int[] words = {parentWord & 0xFFFF, inWords[1], inWords[2], inWords[3]};
        byte[] frame = new byte[9];
        frame[0] = (byte) FRAME_RESULT;
        for (int i = 0; i < 4; i++) {
            frame[1 + i * 2] = (byte) words[i];
            frame[2 + i * 2] = (byte) (words[i] >>> 8);
        }
        connection.send(frame);
        return words;
    }

    /// Pumps the transport and tracks connect/disconnect transitions.
    public void tick() {
        connection.tick();
        boolean now = connection.isConnected();
        if (connection instanceof LinkPollingConnection polling) {
            // Poll briskly while hosting (even with no children yet) so a joiner is accepted
            // promptly; idle only when fully disconnected.
            polling.setLinkPollMode(connection.isActive() ? LinkPollMode.CONNECTED : LinkPollMode.IDLE);
        }
        if (wasConnected && !now) {
            resetSession();
            if (serial != null) {
                serial.onLinkDisconnected();
            }
        }
        wasConnected = now;
    }

    @Override
    public void onFrame(int peer, byte[] data) {
        if (peer < 0 || peer >= rx.length) {
            return;
        }
        ByteArrayOutputStream buffer = rx[peer];
        buffer.writeBytes(data);
        byte[] bytes = buffer.toByteArray();
        int pos = 0;
        while (pos < bytes.length) {
            int type = bytes[pos] & 0xFF;
            int length = frameLength(type);
            if (length <= 0) {
                pos++; // unknown byte: resync by skipping it
                continue;
            }
            if (pos + length > bytes.length) {
                break; // wait for the rest of this frame
            }
            handleFrame(bytes, pos, type, peer);
            pos += length;
        }
        buffer.reset();
        if (pos < bytes.length) {
            buffer.write(bytes, pos, bytes.length - pos);
        }
    }

    @Override
    public void onPeerConnected(int peer) {
        // Host side: tell the freshly-accepted child its multiplayer id (= its slot).
        if (connection.isHosting() && peer >= 1 && peer < MAX_PLAYERS) {
            connection.sendTo(peer, new byte[]{(byte) FRAME_ASSIGN, (byte) peer});
        }
    }

    @Override
    public void onPeerDisconnected(int peer) {
        if (peer >= 0 && peer < inWords.length) {
            inWords[peer] = ABSENT;
            rx[peer].reset();
        }
    }

    private void handleFrame(byte[] buffer, int pos, int type, int peer) {
        switch (type) {
            case FRAME_WORD -> {
                int word = (buffer[pos + 2] & 0xFF) | ((buffer[pos + 3] & 0xFF) << 8);
                // Place by the source slot (robust against a child that lies about its id). On a
                // host, peer is the child's id 1..3; a client never receives WORD frames.
                if (peer >= 0 && peer < inWords.length) {
                    inWords[peer] = word;
                }
            }
            case FRAME_RESULT -> {
                int[] words = new int[4];
                for (int i = 0; i < 4; i++) {
                    words[i] = (buffer[pos + 1 + i * 2] & 0xFF) | ((buffer[pos + 2 + i * 2] & 0xFF) << 8);
                }
                if (serial != null) {
                    serial.onMultiplayerResult(words);
                }
            }
            case FRAME_ASSIGN -> assignedId = buffer[pos + 1] & 0xFF;
            case FRAME_NORMAL_REQ -> {
                if (serial != null) {
                    serial.onNormalRequest(readWord32(buffer, pos + 1));
                }
            }
            case FRAME_NORMAL_RSP -> {
                if (serial != null) {
                    serial.onNormalResponse(readWord32(buffer, pos + 1));
                }
            }
            default -> {
            }
        }
    }

    private static int readWord32(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private void switchConnection(PhysicalConnection newConnection) {
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
        }
        connection = newConnection;
        resetSession();
        // Set the listener last, after state is clean, so any reentrant onPeerConnected/onFrame
        // (e.g. the in-memory star wiring its ASSIGN) operates on a fresh session.
        connection.setListener(this);
    }

    private void resetSession() {
        Arrays.fill(inWords, ABSENT);
        for (ByteArrayOutputStream buffer : rx) {
            buffer.reset();
        }
        assignedId = -1;
        wasConnected = false;
    }

    private static int frameLength(int type) {
        return switch (type) {
            case FRAME_WORD -> 4;
            case FRAME_RESULT -> 9;
            case FRAME_ASSIGN -> 2;
            case FRAME_NORMAL_REQ, FRAME_NORMAL_RSP -> 5;
            default -> -1;
        };
    }

    private static int[] newAbsentWords() {
        int[] words = new int[MAX_PLAYERS];
        Arrays.fill(words, ABSENT);
        return words;
    }

    private static ByteArrayOutputStream[] newRxBuffers() {
        ByteArrayOutputStream[] buffers = new ByteArrayOutputStream[MAX_PLAYERS];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new ByteArrayOutputStream();
        }
        return buffers;
    }
}
