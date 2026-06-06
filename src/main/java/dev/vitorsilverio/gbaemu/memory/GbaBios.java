package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.Arrays;
import java.util.Objects;

public final class GbaBios implements MemorySpace {
    private static final int START = 0x00000000;
    private static final int END   = 0x00003FFF;
    public static final int SIZE   = 16 * 1024;

    private final byte[] data;

    public GbaBios(byte[] bios) {
        this.data = Arrays.copyOf(Objects.requireNonNull(bios), SIZE);
    }

    @Override
    public boolean contains(int address) {
        return Integer.compareUnsigned(address, START) >= 0
                && Integer.compareUnsigned(address, END) <= 0;
    }

    @Override
    public int readByte(int address) {
        return data[GbaMemoryRegion.BIOS.offset(address)] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        // BIOS is read-only
    }
}
