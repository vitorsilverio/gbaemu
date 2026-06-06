package dev.vitorsilverio.gbaemu.interrupt;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

/// Controlador dos registradores IE/IF/IME.
public final class GbaInterruptController implements MemorySpace {
    public static final int IE  = 0x04000200;
    public static final int IF  = 0x04000202;
    public static final int IME = 0x04000208;

    // Owns: IE (0x200-0x201), IF (0x202-0x203), IME (0x208-0x20B)
    private int ie;
    private int interruptFlags;
    private int ime;

    @Override
    public boolean contains(int address) {
        return (address >= IE && address <= IF + 1)
                || (address >= IME && address <= IME + 3);
    }

    @Override
    public int readByte(int address) {
        return readHalfWord(address & ~1) >>> ((address & 1) * 8) & 0xFF;
    }

    @Override
    public int readHalfWord(int address) {
        int aligned = address & ~1;
        if (aligned == IE)  return ie & 0x3FFF;
        if (aligned == IF)  return interruptFlags & 0x3FFF;
        if (aligned == IME) return ime & 1;
        return 0;
    }

    @Override
    public void writeByte(int address, int value) {
        int aligned = address & ~1;
        if (aligned == IF) {
            clearInterruptFlags((value & 0xFF) << ((address & 1) * 8));
            return;
        }
        int shift = (address & 1) * 8;
        int current = readHalfWord(aligned);
        int mask = 0xFF << shift;
        writeHalfWord(aligned, (current & ~mask) | ((value & 0xFF) << shift));
    }

    @Override
    public void writeHalfWord(int address, int value) {
        int aligned = address & ~1;
        if (aligned == IE)  { ie = value & 0x3FFF; return; }
        if (aligned == IF)  { clearInterruptFlags(value); return; }
        if (aligned == IME) { ime = value & 1; }
    }

    public void setMasterEnable(boolean enabled) {
        ime = enabled ? 1 : 0;
    }

    public void enable(GbaInterrupt interrupt) {
        ie |= interrupt.mask();
    }

    public void disable(GbaInterrupt interrupt) {
        ie &= ~interrupt.mask();
    }

    public void request(GbaInterrupt interrupt) {
        request(interrupt.mask());
    }

    public void request(int mask) {
        interruptFlags |= mask & 0x3FFF;
    }

    public void acknowledge(int mask) {
        clearInterruptFlags(mask & 0x3FFF);
    }

    public boolean pending() {
        return (ime & 1) != 0 && (ie & interruptFlags & 0x3FFF) != 0;
    }

    private void clearInterruptFlags(int mask) {
        interruptFlags &= ~(mask & 0x3FFF);
    }
}
