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
        } else {
            halted = false;
            stopped = true;
        }
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
}
