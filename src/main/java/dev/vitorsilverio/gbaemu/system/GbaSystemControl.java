package dev.vitorsilverio.gbaemu.system;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Registradores simples de controle do sistema GBA.
public final class GbaSystemControl {
    public static final int POSTFLG = 0x04000300;
    public static final int HALTCNT = 0x04000301;
    public static final int WAITCNT = 0x04000204;

    private static final int POSTFLG_MASK = 1;
    private static final int HALT_MODE_BIT = 1 << 7;

    private final AddressSpace memory;
    private boolean halted;
    private boolean stopped;

    public GbaSystemControl(AddressSpace memory) {
        this.memory = memory;
    }

    public boolean postBootFlag() {
        return (memory.read8(POSTFLG) & POSTFLG_MASK) != 0;
    }

    public void setPostBootFlag(boolean value) {
        memory.write8(POSTFLG, value ? POSTFLG_MASK : 0);
    }

    public int waitControl() {
        return memory.read16(WAITCNT);
    }

    public void setWaitControl(int value) {
        memory.write16(WAITCNT, value);
    }

    public void writeHaltControl(int value) {
        memory.write8(HALTCNT, value);
        if ((value & HALT_MODE_BIT) == 0) {
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
