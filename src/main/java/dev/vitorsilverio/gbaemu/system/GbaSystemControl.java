package dev.vitorsilverio.gbaemu.system;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

/// Registradores de controle do sistema GBA (POSTFLG, HALTCNT, WAITCNT).
public final class GbaSystemControl implements MemorySpace {
    public static final int POSTFLG = 0x04000300;
    public static final int HALTCNT = 0x04000301;
    public static final int WAITCNT = 0x04000204;

    private int postflg;
    private int waitcnt;
    private boolean halted;
    private boolean stopped;
    /// IntrWait/VBlankIntrWait state: `intrWaitMask` = interrupts the wait returns on (0 =
    /// plain HALT, no specific wait); `intrWaitSatisfied` = one of them has fired since the
    /// wait began. The CPU still wakes on ANY interrupt to service unrelated handlers; the
    /// wait only returns to the game once `intrWaitSatisfied` is set.
    private int intrWaitMask;
    private boolean intrWaitSatisfied;

    @Override
    public boolean contains(int address) {
        return (address & ~1) == WAITCNT
                || address == POSTFLG
                || address == HALTCNT;
    }

    @Override
    public int readByte(int address) {
        if (address == POSTFLG) return postflg & 1;
        if (address == HALTCNT) return 0;
        if (address == WAITCNT)     return waitcnt & 0xFF;
        if (address == WAITCNT + 1) return (waitcnt >>> 8) & 0xFF;
        return 0;
    }

    @Override
    public void writeByte(int address, int value) {
        if (address == POSTFLG) { postflg = value & 1; return; }
        if (address == HALTCNT) { writeHaltControl(value); return; }
        if (address == WAITCNT)     { waitcnt = (waitcnt & 0xFF00) | (value & 0xFF); return; }
        if (address == WAITCNT + 1) { waitcnt = (waitcnt & 0x00FF) | ((value & 0xFF) << 8); }
    }

    public boolean postBootFlag() {
        return (postflg & 1) != 0;
    }

    public void setPostBootFlag(boolean value) {
        postflg = value ? 1 : 0;
    }

    public int waitControl() {
        return waitcnt;
    }

    public void setWaitControl(int value) {
        waitcnt = value & 0xFFFF;
    }

    public void writeHaltControl(int value) {
        if ((value & 0x80) == 0) {
            halted = true;
            stopped = false;
            intrWaitMask = 0; // plain HALT: wake on any enabled interrupt
            intrWaitSatisfied = false;
        } else {
            halted = false;
            stopped = true;
        }
    }

    /// Begin (or re-arm) an IntrWait/VBlankIntrWait halt that returns only once one of the
    /// interrupts in `mask` has fired. Re-armed on each wake while still waiting.
    public void beginIntrWait(int mask) {
        halted = true;
        stopped = false;
        intrWaitMask = mask;
        intrWaitSatisfied = false;
    }

    /// Interrupts the current IntrWait is waiting to return on (0 = plain HALT / not waiting).
    public int intrWaitMask() {
        return intrWaitMask;
    }

    public boolean intrWaitSatisfied() {
        return intrWaitSatisfied;
    }

    public void markIntrWaitSatisfied() {
        intrWaitSatisfied = true;
    }

    public void endIntrWait() {
        intrWaitMask = 0;
        intrWaitSatisfied = false;
    }

    public boolean halted() {
        return halted;
    }

    public boolean stopped() {
        return stopped;
    }

    public void resume() {
        halted = false;
        stopped = false;
    }

    /// Serializes the system-control state (POSTFLG/WAITCNT + halt/intr-wait) into a save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(postflg);
        out.writeInt(waitcnt);
        out.writeBoolean(halted);
        out.writeBoolean(stopped);
        out.writeInt(intrWaitMask);
        out.writeBoolean(intrWaitSatisfied);
    }

    /// Restores the system-control state from a save state.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        postflg = in.readInt();
        waitcnt = in.readInt();
        halted = in.readBoolean();
        stopped = in.readBoolean();
        intrWaitMask = in.readInt();
        intrWaitSatisfied = in.readBoolean();
    }
}
