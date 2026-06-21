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
    private static final int OPEN_BUS_32 = 0xFFFFFFFF; // SIODATA32 read when nothing drove the line
    // SIOCNT bits the game owns: baud (0-1), transfer mode (12-13) and the completion IRQ (14).
    // The start/busy bit 7 and the status bits 2-6 are managed here, not stored.
    private static final int SIOCNT_CONTROL_MASK = 0x7003;
    private static final int START = 0x80;
    private static final int IRQ_ENABLE = 0x4000;
    // Normal-mode SIOCNT clock bits: bit0 selects the shift clock (0=external/slave, 1=internal/
    // master), bit1 the internal clock rate (0=~256 kHz, 1=~2 MHz).
    private static final int NORMAL_INTERNAL_CLOCK = 0x01;
    private static final int NORMAL_FAST_CLOCK = 0x02;
    // Generous safety timeout so a child never hangs forever if the parent stops driving.
    private static final int CHILD_TIMEOUT_CYCLES = 280_896 * 8;
    // How long a connected Normal-mode master waits for the slave's response before giving up and
    // reading open-bus (the peer may not be in Normal mode). ~6 ms — far longer than a local
    // round-trip, but short enough not to stall the game when the peer never answers.
    private static final int NORMAL_LINK_TIMEOUT_CYCLES = 100_000;
    // Opt-in tracing (`-Dgba.serial.debug=true`) for diagnosing what the BIOS/game does with SIO.
    private static final boolean DEBUG = Boolean.getBoolean("gba.serial.debug");

    private enum Phase {
        IDLE, HOST_PENDING, SOLO_PENDING, CHILD_WAIT, NORMAL_PENDING, NORMAL_SLAVE_WAIT, NORMAL_MASTER_WAIT
    }

    private final GbaInterruptController interrupts;
    private GbaLink link;

    private final int[] siomulti = {ABSENT, ABSENT, ABSENT, ABSENT};
    private int siocntControl;
    private int siomltSend;
    private int rcnt;
    private int lastId;

    private boolean transferActive;
    private Phase phase = Phase.IDLE;
    private int transferCyclesRemaining;
    private int childTimeoutRemaining;
    private int dbgLastSiocntRead = Integer.MIN_VALUE;
    private int dbgLastRcntRead = Integer.MIN_VALUE;

    public GbaSerial(GbaInterruptController interrupts) {
        this.interrupts = interrupts;
        this.link = new GbaLink();
        this.link.attachSerial(this);
    }

    /// The link broker; the desktop app uses it to host/join/disconnect a session.
    public GbaLink link() {
        return link;
    }

    /// Replaces this peripheral's link with a shared, app-owned one. The GBA link cable is a
    /// physical connection that exists independent of any game, so the desktop app owns a single
    /// {@link GbaLink} ("the cable") and hands it to every console it boots — the live connection
    /// then persists across ROM loads/restarts. Re-points the link's serial listener here so the
    /// freshly-booted game sees the cable from its first cycle.
    public void adoptLink(GbaLink sharedLink) {
        this.link = sharedLink;
        sharedLink.attachSerial(this);
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
        int result = switch (address & ~1) {
            case SIO_BASE -> siomulti[0];
            case SIO_BASE + 2 -> siomulti[1];
            case SIO_BASE + 4 -> siomulti[2];
            case SIO_BASE + 6 -> siomulti[3];
            case SIOCNT -> visibleSiocnt();
            case SIOMLT_SEND -> siomltSend;
            case RCNT -> rcnt;
            default -> 0;
        };
        if (DEBUG) {
            debugRead(address & ~1, result);
        }
        return result;
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
        if (DEBUG) {
            System.err.printf("[serial] write 0x%07X = 0x%04X%n", address & ~1, value);
        }
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
            case NORMAL_PENDING -> {
                transferCyclesRemaining -= cycles;
                if (transferCyclesRemaining <= 0) {
                    completeNormal(OPEN_BUS_32); // nothing attached → open-bus
                }
            }
            case NORMAL_MASTER_WAIT -> {
                transferCyclesRemaining -= cycles;
                if (transferCyclesRemaining <= 0) {
                    completeNormal(OPEN_BUS_32); // peer never responded → open-bus
                }
            }
            case CHILD_WAIT -> {
                childTimeoutRemaining -= cycles;
                if (childTimeoutRemaining <= 0) {
                    completeMultiplayer(failWords(), link.localId());
                }
            }
            // NORMAL_SLAVE_WAIT: no external clock attached → stays busy, never completes (no IRQ).
            default -> {
            }
        }
    }

    @Override
    public void onMultiplayerResult(int[] words) {
        // A parent-driven transfer reached this unit. On real hardware a child receives the data
        // AND a SERIAL IRQ on *every* parent transfer, whether or not it set the start bit —
        // children are reactive, the parent drives. So always complete here (latch SIOMULTI0-3,
        // learn our id, raise the IRQ), not only when the child explicitly armed a CHILD_WAIT.
        // (Earlier code raised no IRQ for an un-armed child, which hung games whose child code
        // waits on the serial interrupt — e.g. Mario Kart's link screen.) Ignore results that
        // arrive while not in multiplayer mode, since SIOMULTI0-3 alias SIODATA32 there.
        if (!isMultiplayerMode()) {
            return;
        }
        completeMultiplayer(words, link.localId());
    }

    @Override
    public void onNormalRequest(int data) {
        // The peer is the Normal-mode master and is driving a transfer; we are the slave. Real
        // hardware exchanges both words simultaneously, so send our outgoing word back and latch
        // the master's. An armed slave (it wrote the start bit) completes its transfer + IRQ; an
        // un-armed unit just keeps SIODATA fresh (no IRQ) — only meaningful in Normal mode.
        if (!isNormalMode()) {
            return;
        }
        link.sendNormalResponse(normalOutgoing());
        if (transferActive && phase == Phase.NORMAL_SLAVE_WAIT) {
            completeNormal(data);
        } else {
            latchNormalReceived(data);
        }
    }

    @Override
    public void onNormalResponse(int data) {
        // The peer (our slave) answered the transfer we drove as Normal-mode master.
        if (transferActive && phase == Phase.NORMAL_MASTER_WAIT) {
            completeNormal(data);
        }
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
        if (DEBUG) {
            System.err.printf("[serial] beginTransfer siocnt=0x%04X mode=%d mp=%b normal=%b connected=%b%n",
                    siocntControl, subMode(), isMultiplayerMode(), isNormalMode(), link.isConnected());
        }
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
        transferActive = true;
        if ((siocntControl & NORMAL_INTERNAL_CLOCK) != 0) {
            // Master (internal shift clock). With a peer attached, drive the transfer over the
            // link and wait for its response (timeout falls back to open-bus if it never answers,
            // e.g. the peer is not in Normal mode). With nothing attached the line reads open-bus
            // after the bit time — never instantaneous, or a master re-issuing from its serial
            // handler never yields the CPU.
            if (link.isConnected()) {
                // Set the waiting phase BEFORE sending: a synchronous transport (the in-memory
                // test pair) delivers the slave's response reentrantly inside sendNormalRequest,
                // and onNormalResponse only completes when the phase is already NORMAL_MASTER_WAIT.
                phase = Phase.NORMAL_MASTER_WAIT;
                transferCyclesRemaining = NORMAL_LINK_TIMEOUT_CYCLES;
                link.sendNormalRequest(normalOutgoing());
            } else {
                phase = Phase.NORMAL_PENDING;
                transferCyclesRemaining = normalTransferCycles();
            }
        } else {
            // Slave (external shift clock): the transfer completes only when a master drives it
            // (an incoming Normal request over the link, see onNormalRequest). With nothing
            // attached the clock never arrives, so it stays busy forever and raises NO interrupt —
            // exactly like real hardware. This is critical at boot: the GBA BIOS probes for a
            // multiboot host with repeated slave-mode transfers; completing them instantly + IRQ
            // storms the CPU so the BIOS never times out to boot the cart (every game black-screened).
            phase = Phase.NORMAL_SLAVE_WAIT;
        }
    }

    /// Completes a Normal-mode transfer, latching {@code received} into SIODATA (32-bit) or
    /// SIODATA8 (8-bit). {@code 0xFFFFFFFF} is the open-bus value read when nothing responded.
    private void completeNormal(int received) {
        latchNormalReceived(received);
        transferActive = false;
        phase = Phase.IDLE;
        requestIrq();
    }

    /// This unit's outgoing Normal-mode word: SIODATA32 (0x120-0x123) in 32-bit mode, the low byte
    /// of SIODATA8 (0x12A) in 8-bit mode.
    private int normalOutgoing() {
        if (subMode() == 1) {
            return (siomulti[0] & 0xFFFF) | (siomulti[1] << 16);
        }
        return siomltSend & 0xFF;
    }

    private void latchNormalReceived(int received) {
        if (subMode() == 1) {
            siomulti[0] = received & 0xFFFF;
            siomulti[1] = (received >>> 16) & 0xFFFF;
        } else {
            siomltSend = received & 0xFF;
        }
    }

    /// Bit time of a Normal-mode transfer: 8 or 32 bits at the selected internal clock (bit1:
    /// 0 = ~256 kHz, 1 = ~2 MHz). Approximate — it only needs to be non-zero so the CPU advances.
    private int normalTransferCycles() {
        int bits = subMode() == 1 ? 32 : 8; // mode 0 = Normal 8-bit, mode 1 = Normal 32-bit
        int cyclesPerBit = (siocntControl & NORMAL_FAST_CLOCK) != 0 ? 8 : 64;
        return bits * cyclesPerBit;
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
            if (DEBUG) {
                System.err.println("[serial] SERIAL IRQ raised");
            }
        }
    }

    private void debugRead(int aligned, int result) {
        if (aligned == SIOCNT && result != dbgLastSiocntRead) {
            dbgLastSiocntRead = result;
            System.err.printf("[serial] read SIOCNT=0x%04X (mode=%d, mp=%b, normal=%b)%n",
                    result, subMode(), isMultiplayerMode(), isNormalMode());
        } else if (aligned == RCNT && result != dbgLastRcntRead) {
            dbgLastRcntRead = result;
            System.err.printf("[serial] read RCNT=0x%04X (sioMode=%b)%n", result, isSioMode());
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
