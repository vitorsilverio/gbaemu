package dev.vitorsilverio.gbaemu.cartridge;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.Arrays;
import java.util.Objects;

/// ROM do cartucho mapeada nas tres regioes de waitstate (WS0/WS1/WS2).
public final class GbaRom implements MemorySpace {
    private static final int START = 0x08000000;
    private static final int END   = 0x0DFFFFFF;
    public static final int MAX_SIZE = 32 * 1024 * 1024;

    private final byte[] data;
    private int openBusValue;

    public GbaRom(byte[] rom) {
        Objects.requireNonNull(rom, "rom");
        this.data = Arrays.copyOf(rom, Math.min(rom.length, MAX_SIZE));
    }

    public void setOpenBusValue(int value) {
        this.openBusValue = value;
    }

    @Override
    public boolean contains(int address) {
        return Integer.compareUnsigned(address, START) >= 0
                && Integer.compareUnsigned(address, END) <= 0;
    }

    @Override
    public int readByte(int address) {
        int offset = romOffset(address);
        if (offset >= data.length) {
            return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
        }
        return data[offset] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        // ROM is read-only
    }

    private int romOffset(int address) {
        // All three waitstate mirrors map to the same physical ROM
        return (address - START) & (MAX_SIZE - 1);
    }
}
