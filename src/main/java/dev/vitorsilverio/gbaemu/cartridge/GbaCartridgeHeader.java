package dev.vitorsilverio.gbaemu.cartridge;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Header fixo do cartucho GBA localizado em 080000A0-080000BF.
public record GbaCartridgeHeader(
        String title,
        String gameCode,
        String makerCode,
        int fixedValue,
        int unitCode,
        int deviceType,
        int version,
        int complement,
        boolean complementValid) {
    private static final int TITLE_OFFSET = 0xA0;
    private static final int TITLE_LENGTH = 12;
    private static final int GAME_CODE_OFFSET = 0xAC;
    private static final int GAME_CODE_LENGTH = 4;
    private static final int MAKER_CODE_OFFSET = 0xB0;
    private static final int MAKER_CODE_LENGTH = 2;
    private static final int FIXED_VALUE_OFFSET = 0xB2;
    private static final int UNIT_CODE_OFFSET = 0xB3;
    private static final int DEVICE_TYPE_OFFSET = 0xB4;
    private static final int VERSION_OFFSET = 0xBC;
    private static final int COMPLEMENT_OFFSET = 0xBD;

    public static GbaCartridgeHeader parse(byte[] rom) {
        if (rom.length < 0xC0) {
            throw new IllegalArgumentException("GBA ROM must contain at least 0xC0 bytes for the cartridge header");
        }

        int complement = rom[COMPLEMENT_OFFSET] & 0xFF;
        return new GbaCartridgeHeader(
                ascii(rom, TITLE_OFFSET, TITLE_LENGTH),
                ascii(rom, GAME_CODE_OFFSET, GAME_CODE_LENGTH),
                ascii(rom, MAKER_CODE_OFFSET, MAKER_CODE_LENGTH),
                rom[FIXED_VALUE_OFFSET] & 0xFF,
                rom[UNIT_CODE_OFFSET] & 0xFF,
                rom[DEVICE_TYPE_OFFSET] & 0xFF,
                rom[VERSION_OFFSET] & 0xFF,
                complement,
                complement == expectedComplement(rom));
    }

    public static int expectedComplement(byte[] rom) {
        if (rom.length < 0xBE) {
            throw new IllegalArgumentException("GBA ROM must contain at least 0xBE bytes for header complement");
        }

        int checksum = 0;
        for (int address = 0xA0; address <= 0xBC; address++) {
            checksum = (checksum - (rom[address] & 0xFF)) & 0xFF;
        }
        return (checksum - 0x19) & 0xFF;
    }

    public boolean fixedValueValid() {
        return fixedValue == 0x96;
    }

    private static String ascii(byte[] data, int offset, int length) {
        int end = offset + length;
        byte[] bytes = Arrays.copyOfRange(data, offset, end);
        int trimEnd = bytes.length;
        while (trimEnd > 0 && (bytes[trimEnd - 1] == 0 || bytes[trimEnd - 1] == ' ')) {
            trimEnd--;
        }
        return new String(bytes, 0, trimEnd, StandardCharsets.US_ASCII);
    }
}
