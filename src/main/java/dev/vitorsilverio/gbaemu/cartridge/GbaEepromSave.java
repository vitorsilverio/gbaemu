package dev.vitorsilverio.gbaemu.cartridge;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.Arrays;

/// GBA serial EEPROM save, accessed by DMA through the `0x0D000000-0x0DFFFFFF` region (for ROMs
/// up to 16 MB the whole block mirrors the chip). The CPU/DMA streams one bit per 16-bit transfer,
/// in bit 0:
/// - read:  `1 1` + n address bits + `0`, then reads 4 dummy bits + 64 data bits.
/// - write: `1 0` + n address bits + 64 data bits + `0`, then polls reads (1 = ready).
///
/// The address width n (6 for 4 Kbit, 14 for 64 Kbit) is inferred from the first command's length
/// and then fixed, so both chip sizes work without external configuration. Data is organised as
/// 64-bit words; bytes are stored MSB-first within each word (the raw `.sav` layout other
/// emulators use).
public final class GbaEepromSave implements MemorySpace, CartridgeBackup {
    private static final int BASE = 0x0D000000;
    private static final int END  = 0x0DFFFFFF;
    static final int SIZE = 8 * 1024; // 64 Kbit upper bound (also covers 4 Kbit)
    private static final int WORD_COUNT = SIZE / 8;
    private static final int READ_STREAM_BITS = 68; // 4 dummy + 64 data

    private final byte[] data = new byte[SIZE];

    private final boolean[] command = new boolean[128]; // incoming command bit buffer
    private int commandLength;
    private final boolean[] readStream = new boolean[READ_STREAM_BITS];
    private int readIndex;
    private int readRemaining;
    private int addressBits; // 0 until inferred from the first well-formed command

    public GbaEepromSave() {
        Arrays.fill(data, (byte) 0xFF);
    }

    @Override
    public boolean contains(int address) {
        return Integer.compareUnsigned(address, BASE) >= 0 && Integer.compareUnsigned(address, END) <= 0;
    }

    @Override
    public int readHalfWord(int address) {
        // A read command's data is produced lazily on the first read after it was streamed in.
        if (readRemaining == 0 && commandLength > 0) {
            parseCommand();
        }
        if (readRemaining > 0) {
            readRemaining--;
            return readStream[readIndex++] ? 1 : 0;
        }
        return 1; // ready / not busy
    }

    @Override
    public void writeHalfWord(int address, int value) {
        if (commandLength < command.length) {
            command[commandLength++] = (value & 1) != 0;
        }
    }

    private void parseCommand() {
        int len = commandLength;
        commandLength = 0;
        if (len < 3 || !command[0]) {
            return; // not a valid command (must start with a 1)
        }
        boolean isWrite = !command[1]; // "1 0" = write, "1 1" = read
        if (isWrite) {
            int n = lockAddressBits(len - (2 + 64 + 1));
            if (n <= 0 || 2 + n + 64 > len) {
                return;
            }
            int base = (readBits(2, n) % WORD_COUNT) * 8;
            for (int j = 0; j < 8; j++) {
                int b = 0;
                for (int k = 0; k < 8; k++) {
                    b = (b << 1) | (command[2 + n + j * 8 + k] ? 1 : 0);
                }
                data[base + j] = (byte) b;
            }
            // Write complete; subsequent reads return the default 1 (ready).
        } else {
            int n = lockAddressBits(len - (2 + 1));
            if (n <= 0) {
                return;
            }
            int base = (readBits(2, n) % WORD_COUNT) * 8;
            for (int i = 0; i < 4; i++) {
                readStream[i] = false; // 4 leading dummy bits
            }
            for (int j = 0; j < 8; j++) {
                int b = data[base + j] & 0xFF;
                for (int k = 0; k < 8; k++) {
                    readStream[4 + j * 8 + k] = ((b >> (7 - k)) & 1) != 0;
                }
            }
            readIndex = 0;
            readRemaining = READ_STREAM_BITS;
        }
    }

    /// Locks the chip's address width on the first command whose length implies 6 or 14 bits.
    private int lockAddressBits(int candidate) {
        if (addressBits == 0 && (candidate == 6 || candidate == 14)) {
            addressBits = candidate;
        }
        return addressBits != 0 ? addressBits : candidate;
    }

    private int readBits(int start, int n) {
        int value = 0;
        for (int i = 0; i < n; i++) {
            value = (value << 1) | (command[start + i] ? 1 : 0);
        }
        return value;
    }

    // EEPROM is a 1-bit serial port reached via 16-bit DMA; byte/word accesses just mirror it.
    @Override public int readByte(int address) { return readHalfWord(address & ~1) & 0xFF; }
    @Override public void writeByte(int address, int value) { writeHalfWord(address & ~1, value); }
    @Override public int readWord(int address) { return readHalfWord(address) | (readHalfWord(address + 2) << 16); }
    @Override public void writeWord(int address, int value) { writeHalfWord(address, value); writeHalfWord(address + 2, value >>> 16); }

    public byte[] snapshot() {
        return Arrays.copyOf(data, data.length);
    }

    public void load(byte[] bytes) {
        Arrays.fill(data, (byte) 0xFF);
        System.arraycopy(bytes, 0, data, 0, Math.min(bytes.length, data.length));
    }

    @Override
    public boolean isPersistable() {
        return true; // an EEPROM cartridge always has a save worth persisting
    }
}
