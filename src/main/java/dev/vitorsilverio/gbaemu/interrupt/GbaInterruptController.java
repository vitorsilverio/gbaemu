package dev.vitorsilverio.gbaemu.interrupt;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

/// Controlador inicial dos registradores IE/IF/IME.
public final class GbaInterruptController {
    public static final int IE = 0x04000200;
    public static final int IF = 0x04000202;
    public static final int IME = 0x04000208;

    private final AddressSpace memory;

    public GbaInterruptController(AddressSpace memory) {
        this.memory = memory;
    }

    public void setMasterEnable(boolean enabled) {
        memory.write16(IME, enabled ? 1 : 0);
    }

    public void enable(GbaInterrupt interrupt) {
        memory.write16(IE, memory.read16(IE) | interrupt.mask());
    }

    public void disable(GbaInterrupt interrupt) {
        memory.write16(IE, memory.read16(IE) & ~interrupt.mask());
    }

    public void request(GbaInterrupt interrupt) {
        request(interrupt.mask());
    }

    public void request(int mask) {
        int next = memory.read16(IF) | (mask & 0x3FFF);
        memory.write8(IF, next);
        memory.write8(IF + 1, next >>> 8);
    }

    public void acknowledge(int mask) {
        memory.write16(IF, mask & 0x3FFF);
    }

    public boolean pending() {
        return (memory.read16(IME) & 1) != 0
                && (memory.read16(IE) & memory.read16(IF) & 0x3FFF) != 0;
    }
}
