package dev.vitorsilverio.gbaemu.cartridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaCartridgeHeaderTest {
    @Test
    void parsesTitleGameCodeMakerCodeAndFlags() {
        byte[] rom = sampleRom();

        GbaCartridgeHeader header = GbaCartridgeHeader.parse(rom);

        assertEquals("TEST GAME", header.title());
        assertEquals("ABCD", header.gameCode());
        assertEquals("01", header.makerCode());
        assertEquals(0x96, header.fixedValue());
        assertTrue(header.fixedValueValid());
        assertTrue(header.complementValid());
    }

    @Test
    void detectsInvalidComplement() {
        byte[] rom = sampleRom();
        rom[0xBD] ^= 1;

        GbaCartridgeHeader header = GbaCartridgeHeader.parse(rom);

        assertFalse(header.complementValid());
    }

    @Test
    void rejectsRomTooSmallForHeader() {
        assertThrows(IllegalArgumentException.class, () -> GbaCartridgeHeader.parse(new byte[0xBF]));
    }

    @Test
    void cartridgeCopiesRomInput() {
        byte[] rom = sampleRom();
        GbaCartridge cartridge = GbaCartridge.load(rom);

        rom[0xA0] = 'X';

        assertEquals("TEST GAME", cartridge.header().title());
        assertEquals('T', cartridge.rom()[0xA0]);
    }

    private static byte[] sampleRom() {
        byte[] rom = new byte[0xC0];
        putAscii(rom, 0xA0, 12, "TEST GAME");
        putAscii(rom, 0xAC, 4, "ABCD");
        putAscii(rom, 0xB0, 2, "01");
        rom[0xB2] = (byte) 0x96;
        rom[0xBC] = 1;
        rom[0xBD] = (byte) GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }

    private static void putAscii(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(length, bytes.length));
    }
}
