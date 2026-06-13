package dev.vitorsilverio.gbaemu.link;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/// Brokers the GBA SIO **multiplayer-mode** exchange over a {@link PhysicalConnection}. Unlike
/// the Game Boy's byte-at-a-time master/slave clocking, multiplayer mode is a framed exchange:
/// the parent (host, id 0) starts a transfer, every unit contributes one 16-bit word, and all
/// units receive all words (absent slots read {@code 0xFFFF}) — which is why it is far less
/// cycle-sync sensitive than the GB link.
///
/// Wire protocol (tiny fixed frames):
/// <ul>
///   <li>{@code WORD}  = {@code 0x01, senderId, lo, hi} — a unit's latest SIOMLT_SEND.</li>
///   <li>{@code RESULT}= {@code 0x02, w0lo,w0hi, w1lo,w1hi, w2lo,w2hi, w3lo,w3hi} — the parent's
///       assembled SIOMULTI0-3, broadcast on transfer completion.</li>
/// </ul>
/// The {@code inWords} cache and the 4-word result are sized for up to 4 players from the
/// start; only the 2-player path (host id 0 + one child id 1) is wired today. Multi-child
/// relay (4P) and the wireless adapter (RFU, a Normal-mode device) plug in later without
/// changing this contract.
///
/// All methods run on the emulation thread (the peripheral writes, the tick, and the socket
/// drain are all driven from there); {@code connection} is volatile only because the UI reads
/// {@link #status()} / {@link #isConnected()} from the AWT thread.
public final class GbaLink {

    private static final int FRAME_WORD = 0x01;
    private static final int FRAME_RESULT = 0x02;
    private static final int ABSENT = 0xFFFF;

    private volatile PhysicalConnection connection = new DisconnectedPhysicalConnection();
    private SerialLinkListener serial;
    private final int[] inWords = newAbsentWords();
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
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

    /// Replaces the live transport (used by tests to inject an in-memory endpoint).
    public void useConnection(PhysicalConnection newConnection) {
        switchConnection(newConnection);
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

    /// This unit's multiplayer id: 0 for the parent (host), 1 for the single child (2-player).
    public int localId() {
        return connection.isHosting() ? 0 : 1;
    }

    /// Publishes this unit's outgoing word so the peer can include it in the next exchange.
    public void sendWord(int word) {
        if (connection.isConnected()) {
            connection.send(new byte[]{(byte) FRAME_WORD, (byte) localId(), (byte) word, (byte) (word >>> 8)});
        }
    }

    /// Parent-only: assembles SIOMULTI0-3 from this unit's word (slot 0) plus the cached child
    /// words, broadcasts the result to the peer, and returns it so the parent latches it too.
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
            polling.setLinkPollMode(now ? LinkPollMode.CONNECTED : LinkPollMode.IDLE);
        }
        if (wasConnected && !now) {
            Arrays.fill(inWords, ABSENT);
            if (serial != null) {
                serial.onLinkDisconnected();
            }
        }
        wasConnected = now;
    }

    private void switchConnection(PhysicalConnection newConnection) {
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
        }
        connection = newConnection;
        connection.setListener(this::onFrame);
        Arrays.fill(inWords, ABSENT);
        rx.reset();
        wasConnected = false;
    }

    private void onFrame(byte[] data) {
        rx.writeBytes(data);
        byte[] buffer = rx.toByteArray();
        int pos = 0;
        while (pos < buffer.length) {
            int type = buffer[pos] & 0xFF;
            int length = frameLength(type);
            if (length <= 0) {
                pos++; // unknown byte: resync by skipping it
                continue;
            }
            if (pos + length > buffer.length) {
                break; // wait for the rest of this frame
            }
            handleFrame(buffer, pos, type);
            pos += length;
        }
        rx.reset();
        if (pos < buffer.length) {
            rx.write(buffer, pos, buffer.length - pos);
        }
    }

    private void handleFrame(byte[] buffer, int pos, int type) {
        if (type == FRAME_WORD) {
            int id = buffer[pos + 1] & 0xFF;
            int word = (buffer[pos + 2] & 0xFF) | ((buffer[pos + 3] & 0xFF) << 8);
            if (id >= 0 && id < inWords.length) {
                inWords[id] = word;
            }
        } else if (type == FRAME_RESULT) {
            int[] words = new int[4];
            for (int i = 0; i < 4; i++) {
                words[i] = (buffer[pos + 1 + i * 2] & 0xFF) | ((buffer[pos + 2 + i * 2] & 0xFF) << 8);
            }
            if (serial != null) {
                serial.onMultiplayerResult(words);
            }
        }
    }

    private static int frameLength(int type) {
        return switch (type) {
            case FRAME_WORD -> 4;
            case FRAME_RESULT -> 9;
            default -> -1;
        };
    }

    private static int[] newAbsentWords() {
        int[] words = new int[4];
        Arrays.fill(words, ABSENT);
        return words;
    }
}
