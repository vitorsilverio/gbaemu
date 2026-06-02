package dev.vitorsilverio.gbaemu.cartridge;

import java.nio.charset.StandardCharsets;

/// Detecta o tipo de save procurando assinaturas conhecidas no binario da ROM.
public final class GbaSaveTypeDetector {
    private static final byte[] SRAM = ascii("SRAM_V");
    private static final byte[] FLASH = ascii("FLASH_V");
    private static final byte[] FLASH512 = ascii("FLASH512_V");
    private static final byte[] FLASH1M = ascii("FLASH1M_V");
    private static final byte[] EEPROM = ascii("EEPROM_V");

    private GbaSaveTypeDetector() {
    }

    public static GbaSaveType detect(byte[] rom) {
        if (contains(rom, FLASH1M)) {
            return GbaSaveType.FLASH_1M;
        }
        if (contains(rom, FLASH512)) {
            return GbaSaveType.FLASH_512;
        }
        if (contains(rom, FLASH)) {
            return GbaSaveType.FLASH;
        }
        if (contains(rom, EEPROM)) {
            return GbaSaveType.EEPROM;
        }
        if (contains(rom, SRAM)) {
            return GbaSaveType.SRAM;
        }
        return GbaSaveType.NONE;
    }

    private static boolean contains(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) {
            return false;
        }
        for (int offset = 0; offset <= data.length - needle.length; offset++) {
            if (matches(data, needle, offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] data, byte[] needle, int offset) {
        for (int i = 0; i < needle.length; i++) {
            if (data[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
