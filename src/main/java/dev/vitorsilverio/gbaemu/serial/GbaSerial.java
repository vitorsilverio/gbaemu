package dev.vitorsilverio.gbaemu.serial;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.link.GbaLink;
import dev.vitorsilverio.gbaemu.link.SerialLinkListener;

/// The GBA Serial I/O peripheral (SIODATA32/SIOMULTI0-3, SIOCNT, SIOMLT_SEND, RCNT). Today it
/// implements **multiplayer mode** fully (the framed up-to-4-player exchange used by e.g. Mario
/// Kart Super Circuit, brokered over TCP by {@link GbaLink}) and a minimal **normal mode** that
/// completes as "nothing attached" (the foundation for the wireless adapter / RFU later).
///
/// When no link is connected every transfer runs solo (the unit becomes parent id 0, the other
/// SIOMULTI slots read {@code 0xFFFF}), so single-player games that poke the serial port behave
/// exactly as before. Register state is snapshotted; the live network session is not.
public final class GbaSerial implements MemorySpace, SerialLinkListener {

    private static final int SIO_BASE = 0x04000120;
    private static final int SIO_END = 0x0400012F;
    private static final int SIOCNT = 0x04000128;
    private static final int SIOMLT_SEND = 0x0400012A;
    private static final int RCNT = 0x04000134;
    private static final int RCNT_END = 0x04000137;

    private static final int ABSENT = 0xFFFF;
    // SIOCNT bits the game owns: baud (0-1), transfer mode (12-13) and the completion IRQ (14).
    // The start/busy bit 7 and the status bits 2-6 are managed here, not stored.
    private static final int SIOCNT_CONTROL_MASK = 0x7003;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;
    // Generous safety timeout so a child never hangs forever if the parent stops driving.
    private static final int CHILD_TIMEOUT_CYCLES = 280_896 * 8;

    private enum Phase { IDLE, HOST_PENDING, SOLO_PENDING, CHILD_WAIT }

    private final GbaInterruptController interrupts;
    private final GbaLink link;

    private final int[] siomulti = {ABSENT, ABSENT, ABSENT, ABSENT};
    private int siocntControl;
    private int siomltSend;
    private int rcnt;
    private int lastId;

    private boolean transferActive;
    private Phase phase = Phase.IDLE;
    private int transferCyclesRemaining;
    private int childTimeoutRemaining;

    public GbaSerial(GbaInterruptController interrupts) {
        this.interrupts = interrupts;
        this.link = new GbaLink();
        this.link.attachSerial(this);
    }

    /// The link broker; the desktop app uses it to host/join/disconnect a session.
    public GbaLink link() {
        return link;
    }

    @Override
    public boolean contains(int address) {
        return (address >= SIO_BASE && address <= SIO_END) || (address >= RCNT && address <= RCNT_END);
    }

    @Override
    public int readByte(int address) {
        return (readHalfWord(address & ~1) >>> ((address & 1) * 8)) & 0xFF;
    }

    @Override
    public int readHalfWord(int address) {
        return switch (address & ~1) {
            case SIO_BASE -> siomulti[0];
            case SIO_BASE + 2 -> siomulti[1];
            case SIO_BASE + 4 -> siomulti[2];
            case SIO_BASE + 6 -> siomulti[3];
            case SIOCNT -> visibleSiocnt();
            case SIOMLT_SEND -> siomltSend;
            case RCNT -> rcnt;
            default -> 0;
        };
    }

    @Override
    public int readWord(int address) {
        return (readHalfWord(address & ~3) & 0xFFFF) | (readHalfWord((address & ~3) + 2) << 16);
    }

    @Override
    public void writeByte(int address, int value) {
        int aligned = address & ~1;
        int shift = (address & 1) * 8;
        int current = readHalfWord(aligned);
        writeHalfWord(aligned, (current & ~(0xFF << shift)) | ((value & 0xFF) << shift));
    }

    @Override
    public void writeHalfWord(int address, int value) {
        value &= 0xFFFF;
        switch (address & ~1) {
            case SIO_BASE -> siomulti[0] = value;     // SIODATA32 low (normal 32-bit send)
            case SIO_BASE + 2 -> siomulti[1] = value; // SIODATA32 high
            case SIO_BASE + 4 -> siomulti[2] = value;
            case SIO_BASE + 6 -> siomulti[3] = value;
            case SIOCNT -> writeSiocnt(value);
            case SIOMLT_SEND -> {
                siomltSend = value;
                link.sendWord(siomltSend);
            }
            case RCNT -> rcnt = value;
            default -> { /* unused */ }
        }
    }

    @Override
    public void writeWord(int address, int value) {
        writeHalfWord(address & ~3, value & 0xFFFF);
        writeHalfWord((address & ~3) + 2, value >>> 16);
    }

    /// Advances any in-flight transfer and pumps the link transport. Called from
    /// {@code GbaConsole#tickHardware} with the cycle count of the block just executed.
    public void tick(int cycles) {
        link.tick();
        if (!transferActive) {
            return;
        }
        switch (phase) {
            case HOST_PENDING, SOLO_PENDING -> {
                transferCyclesRemaining -= cycles;
                if (transferCyclesRemaining <= 0) {
                    completePending();
                }
            }
            case CHILD_WAIT -> {
                childTimeoutRemaining -= cycles;
                if (childTimeoutRemaining <= 0) {
                    completeMultiplayer(failWords(), link.localId());
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void onMultiplayerResult(int[] words) {
        if (transferActive && phase == Phase.CHILD_WAIT) {
            completeMultiplayer(words, link.localId());
            return;
        }
        // Result arrived without an armed transfer: keep SIOMULTI fresh, but raise no IRQ.
        for (int i = 0; i < 4; i++) {
            siomulti[i] = words[i] & 0xFFFF;
        }
        lastId = link.localId();
    }

    @Override
    public void onLinkDisconnected() {
        if (transferActive && phase == Phase.CHILD_WAIT) {
            completeMultiplayer(failWords(), link.localId());
        }
    }

    private void writeSiocnt(int value) {
        siocntControl = value & SIOCNT_CONTROL_MASK;
        boolean start = (value & START) != 0;
        if (start && !transferActive) {
            beginTransfer();
        }
    }

    private void beginTransfer() {
        if (isMultiplayerMode()) {
            beginMultiplayer();
        } else if (isNormalMode()) {
            beginNormal();
        }
        // UART / general-purpose / JOYBUS: no transfer model yet.
    }

    private void beginMultiplayer() {
        transferActive = true;
        if (link.isConnected() && !link.isHosting()) {
            phase = Phase.CHILD_WAIT;
            childTimeoutRemaining = CHILD_TIMEOUT_CYCLES;
        } else {
            phase = link.isConnected() ? Phase.HOST_PENDING : Phase.SOLO_PENDING;
            transferCyclesRemaining = transferCycles();
        }
    }

    private void beginNormal() {
        // Nothing connected: a normal-mode master reads back open-bus (0xFFFF.....) and finishes.
        // Connected normal mode (the wireless adapter / RFU) is a future increment.
        siomulti[0] = ABSENT;
        siomulti[1] = ABSENT;
        transferActive = false;
        phase = Phase.IDLE;
        requestIrq();
    }

    private void completePending() {
        int send = siomltSend & 0xFFFF;
        int[] words = link.startExchange(send); // host: assemble + broadcast + return; solo: null
        if (words == null) {
            words = new int[]{send, ABSENT, ABSENT, ABSENT};
        }
        completeMultiplayer(words, 0); // host / solo are parent id 0
    }

    private void completeMultiplayer(int[] words, int id) {
        for (int i = 0; i < 4; i++) {
            siomulti[i] = words[i] & 0xFFFF;
        }
        lastId = id;
        transferActive = false;
        phase = Phase.IDLE;
        requestIrq();
    }

    private int[] failWords() {
        int[] words = {ABSENT, ABSENT, ABSENT, ABSENT};
        int id = link.localId();
        if (id >= 0 && id < 4) {
            words[id] = siomltSend & 0xFFFF;
        }
        return words;
    }

    private int visibleSiocnt() {
        int value = siocntControl;
        if (transferActive) {
            value |= START;
        }
        if (link.isConnected() && !link.isHosting()) {
            value |= 0x04; // SI terminal: child
        }
        if (link.isConnected()) {
            value |= 0x08; // SD terminal: good connection
        }
        value |= (lastId & 3) << 4;
        return value;
    }

    private boolean isSioMode() {
        return (rcnt & 0x8000) == 0;
    }

    private int subMode() {
        return (siocntControl >>> 12) & 0x3;
    }

    private boolean isMultiplayerMode() {
        return isSioMode() && subMode() == 2;
    }

    private boolean isNormalMode() {
        return isSioMode() && (subMode() == 0 || subMode() == 1);
    }

    private int transferCycles() {
        return switch (siocntControl & 0x3) {
            case 0 -> 8192; // ~9600 baud
            case 1 -> 2048; // ~38400
            case 2 -> 1024; // ~57600
            default -> 512; // ~115200
        };
    }

    private void requestIrq() {
        if (interrupts != null && (siocntControl & IRQ_ENABLE) != 0) {
            interrupts.request(GbaInterrupt.SERIAL);
        }
    }

    /// Serializes the register state (not the live network session) into a save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        for (int word : siomulti) {
            out.writeInt(word);
        }
        out.writeInt(siocntControl);
        out.writeInt(siomltSend);
        out.writeInt(rcnt);
        out.writeInt(lastId);
    }

    /// Restores the register state; any in-flight transfer is dropped (the session is not saved).
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < 4; i++) {
            siomulti[i] = in.readInt();
        }
        siocntControl = in.readInt();
        siomltSend = in.readInt();
        rcnt = in.readInt();
        lastId = in.readInt();
        transferActive = false;
        phase = Phase.IDLE;
        transferCyclesRemaining = 0;
        childTimeoutRemaining = 0;
    }
}
