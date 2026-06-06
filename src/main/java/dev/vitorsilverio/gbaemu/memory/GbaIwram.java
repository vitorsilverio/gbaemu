package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

public final class GbaIwram implements MemorySpace {
    public static final int SIZE = 32 * 1024;

    private final byte[] data = new byte[SIZE];

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.IWRAM.contains(address);
    }

    @Override
    public int readByte(int address) {
        return data[GbaMemoryRegion.IWRAM.offset(address)] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        data[GbaMemoryRegion.IWRAM.offset(address)] = (byte) value;
    }
}
