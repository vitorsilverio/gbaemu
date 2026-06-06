package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.memory.GbaMemoryRegion;

import java.util.function.IntSupplier;

final class GbaVram implements MemorySpace {
    static final int SIZE = 96 * 1024;

    private final byte[] data = new byte[SIZE];
    private final IntSupplier displayMode;

    GbaVram(IntSupplier displayMode) {
        this.displayMode = displayMode;
    }

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.VRAM.contains(address);
    }

    @Override
    public int readByte(int address) {
        return data[vramOffset(address)] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        int offset = vramOffset(address);
        if (offset >= objVramStart()) {
            return;
        }
        int aligned = offset & ~1;
        data[aligned] = (byte) value;
        data[aligned + 1] = (byte) value;
    }

    @Override
    public void writeHalfWord(int address, int value) {
        int aligned = address & ~1;
        int offset = vramOffset(aligned);
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    @Override
    public void writeWord(int address, int value) {
        writeHalfWord(address & ~3, value);
        writeHalfWord((address & ~3) + 2, value >>> 16);
    }

    private int vramOffset(int address) {
        int offset = GbaMemoryRegion.VRAM.offset(address);
        if (offset >= SIZE) {
            return 0x10000 + ((offset - SIZE) & 0x7FFF);
        }
        return offset;
    }

    private int objVramStart() {
        int mode = displayMode.getAsInt() & 0x7;
        return mode >= 3 && mode <= 5 ? 0x14000 : 0x10000;
    }
}
