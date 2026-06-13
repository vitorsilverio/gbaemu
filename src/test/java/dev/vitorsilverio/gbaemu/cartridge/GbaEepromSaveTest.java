package dev.vitorsilverio.gbaemu.cartridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaEepromSaveTest {
    private static final int EEPROM_ADDR = 0x0D000000;

    @Test
    void writesThenReadsBackA64BitWordWith14BitAddressing() {
        GbaEepromSave eeprom = new GbaEepromSave();
        long data = 0x0123456789ABCDEFL;

        writeWord(eeprom, 5, 14, data);
        assertEquals(data, readWord(eeprom, 5, 14));
    }

    @Test
    void keepsWordsSeparateAcrossAddresses() {
        GbaEepromSave eeprom = new GbaEepromSave();
        writeWord(eeprom, 0, 6, 0x1111_2222_3333_4444L);
        writeWord(eeprom, 1, 6, 0x5555_6666_7777_8888L);

        assertEquals(0x1111_2222_3333_4444L, readWord(eeprom, 0, 6));
        assertEquals(0x5555_6666_7777_8888L, readWord(eeprom, 1, 6));
    }

    @Test
    void unwrittenWordReadsBackAsAllOnes() {
        GbaEepromSave eeprom = new GbaEepromSave();
        // The chip powers up as 0xFF; a read-only access must not return zeros.
        assertEquals(0xFFFFFFFFFFFFFFFFL, readWord(eeprom, 9, 14));
    }

    @Test
    void snapshotRoundTripsThroughLoad() {
        GbaEepromSave source = new GbaEepromSave();
        writeWord(source, 3, 14, 0xCAFEBABEDEADBEEFL);

        GbaEepromSave restored = new GbaEepromSave();
        restored.load(source.snapshot());
        assertEquals(0xCAFEBABEDEADBEEFL, readWord(restored, 3, 14));
    }

    // --- helpers: drive the serial protocol one bit per 16-bit transfer (bit 0), as the DMA does ---

    private static void sendBit(GbaEepromSave eeprom, int bit) {
        eeprom.writeHalfWord(EEPROM_ADDR, bit & 1);
    }

    private static int receiveBit(GbaEepromSave eeprom) {
        return eeprom.readHalfWord(EEPROM_ADDR) & 1;
    }

    private static void writeWord(GbaEepromSave eeprom, int address, int addressBits, long data) {
        sendBit(eeprom, 1); // "1 0" = write
        sendBit(eeprom, 0);
        for (int i = addressBits - 1; i >= 0; i--) sendBit(eeprom, (address >> i) & 1);
        for (int i = 63; i >= 0; i--) sendBit(eeprom, (int) ((data >>> i) & 1));
        sendBit(eeprom, 0); // stop bit
        receiveBit(eeprom); // the device commits the buffered write on the first poll read
    }

    private static long readWord(GbaEepromSave eeprom, int address, int addressBits) {
        sendBit(eeprom, 1); // "1 1" = read
        sendBit(eeprom, 1);
        for (int i = addressBits - 1; i >= 0; i--) sendBit(eeprom, (address >> i) & 1);
        sendBit(eeprom, 0); // stop bit
        for (int i = 0; i < 4; i++) receiveBit(eeprom); // 4 leading dummy bits
        long value = 0;
        for (int i = 0; i < 64; i++) value = (value << 1) | receiveBit(eeprom);
        return value;
    }
}
