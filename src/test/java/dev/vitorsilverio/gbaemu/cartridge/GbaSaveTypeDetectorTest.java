package dev.vitorsilverio.gbaemu.cartridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GbaSaveTypeDetectorTest {
    @Test
    void detectsNoSaveWhenSignatureIsAbsent() {
        assertEquals(GbaSaveType.NONE, GbaSaveTypeDetector.detect(new byte[0xC0]));
    }

    @Test
    void detectsSramSignature() {
        assertEquals(GbaSaveType.SRAM, GbaSaveTypeDetector.detect(romWith("SRAM_V113")));
    }

    @Test
    void detectsFlashVariantsBeforeGenericFlash() {
        assertEquals(GbaSaveType.FLASH_512, GbaSaveTypeDetector.detect(romWith("FLASH512_V131")));
        assertEquals(GbaSaveType.FLASH_1M, GbaSaveTypeDetector.detect(romWith("FLASH1M_V103")));
        assertEquals(GbaSaveType.FLASH, GbaSaveTypeDetector.detect(romWith("FLASH_V126")));
    }

    @Test
    void detectsEepromSignature() {
        assertEquals(GbaSaveType.EEPROM, GbaSaveTypeDetector.detect(romWith("EEPROM_V124")));
    }

    @Test
    void cartridgeExposesDetectedSaveType() {
        GbaCartridge cartridge = GbaCartridge.load(validHeaderRomWith("EEPROM_V124"));

        assertEquals(GbaSaveType.EEPROM, cartridge.saveType());
    }

    @Test
    void fallsBackToGameCodeOverrideWhenNoMarkerPresent() {
        byte[] rom = new byte[0x200];
        putAscii(rom, 0xAC, "A2CE"); // Castlevania ships without a save-type marker string
        assertEquals(GbaSaveType.SRAM, GbaSaveTypeDetector.detect(rom));
    }

    @Test
    void unknownGameCodeWithoutMarkerStaysNone() {
        byte[] rom = new byte[0x200];
        putAscii(rom, 0xAC, "ZZZZ");
        assertEquals(GbaSaveType.NONE, GbaSaveTypeDetector.detect(rom));
    }

    private static byte[] romWith(String signature) {
        byte[] rom = new byte[0x200];
        putAscii(rom, 0x100, signature);
        return rom;
    }

    private static byte[] validHeaderRomWith(String signature) {
        byte[] rom = romWith(signature);
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
}
