package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

public final class GbaEwram implements MemorySpace {
    public static final int SIZE = 256 * 1024;

    private final byte[] data = new byte[SIZE];

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.EWRAM.contains(address);
    }

    @Override
    public int readByte(int address) {
        return data[GbaMemoryRegion.EWRAM.offset(address)] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        data[GbaMemoryRegion.EWRAM.offset(address)] = (byte) value;
    }
}
