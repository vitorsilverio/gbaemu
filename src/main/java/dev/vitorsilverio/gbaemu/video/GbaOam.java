package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.memory.GbaMemoryRegion;

final class GbaOam implements MemorySpace {
    static final int SIZE = 1024;

    private final byte[] data = new byte[SIZE];

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.OAM.contains(address);
    }

    @Override
    public int readByte(int address) {
        return data[GbaMemoryRegion.OAM.offset(address)] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        // 8-bit writes to OAM are ignored
    }

    @Override
    public void writeHalfWord(int address, int value) {
        int offset = GbaMemoryRegion.OAM.offset(address & ~1);
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    @Override
    public void writeWord(int address, int value) {
        writeHalfWord(address & ~3, value);
        writeHalfWord((address & ~3) + 2, value >>> 16);
    }
}
