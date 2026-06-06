package dev.vitorsilverio.gbaemu.core;

public interface MemorySpace {

    boolean contains(int address);

    int readByte(int address);

    default int readHalfWord(int address) {
        return (readByte(address) & 0xFF) | ((readByte(address + 1) & 0xFF) << 8);
    }

    default int readWord(int address) {
        return (readHalfWord(address) & 0xFFFF) | (readHalfWord(address + 2) << 16);
    }

    void writeByte(int address, int value);

    default void writeHalfWord(int address, int value) {
        writeByte(address, value & 0xFF);
        writeByte(address + 1, (value >>> 8) & 0xFF);
    }

    default void writeWord(int address, int value) {
        writeHalfWord(address, value & 0xFFFF);
        writeHalfWord(address + 2, (value >>> 16) & 0xFFFF);
    }
}
