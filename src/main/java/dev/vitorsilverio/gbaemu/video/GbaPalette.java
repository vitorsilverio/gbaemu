package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.memory.GbaMemoryRegion;

final class GbaPalette implements MemorySpace {
    static final int SIZE = 1024;

    private final byte[] data = new byte[SIZE];

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.PALETTE.contains(address);
    }

    @Override
    public int readByte(int address) {
        return data[GbaMemoryRegion.PALETTE.offset(address)] & 0xFF;
    }

    @Override
    public void writeHalfWord(int address, int value) {
        int offset = GbaMemoryRegion.PALETTE.offset(address) & ~1;
        data[offset]     = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    @Override
    public void writeByte(int address, int value) {
        // 8-bit writes replicate the byte to both halves of the halfword
        int offset = GbaMemoryRegion.PALETTE.offset(address) & ~1;
        data[offset] = (byte) value;
        data[offset + 1] = (byte) value;
    }
}
