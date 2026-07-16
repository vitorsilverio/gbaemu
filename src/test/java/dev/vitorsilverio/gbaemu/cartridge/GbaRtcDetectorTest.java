package dev.vitorsilverio.gbaemu.cartridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaRtcDetectorTest {
    @Test
    void detectsEmeraldRubyAndSapphire() {
        assertTrue(GbaRtcDetector.hasRtc("BPEE")); // Emerald
        assertTrue(GbaRtcDetector.hasRtc("AXVE")); // Ruby
        assertTrue(GbaRtcDetector.hasRtc("AXPE")); // Sapphire
    }

    @Test
    void detectsBoktaiSeries() {
        assertTrue(GbaRtcDetector.hasRtc("U3IJ"));
        assertTrue(GbaRtcDetector.hasRtc("U32J"));
        assertTrue(GbaRtcDetector.hasRtc("U33J"));
    }

    @Test
    void fireRedHasNoRtc() {
        // Regressao: os 5 jogos de referencia (FireRed incluso) continuam sem RTC.
        assertFalse(GbaRtcDetector.hasRtc("BPRE"));
    }

    @Test
    void cartridgeExposesHasRtc() {
        GbaCartridge withRtc = GbaCartridge.load(validHeaderRomWithGameCode("BPEE"));
        GbaCartridge withoutRtc = GbaCartridge.load(validHeaderRomWithGameCode("BPRE"));

        assertTrue(withRtc.hasRtc());
        assertFalse(withoutRtc.hasRtc());
    }

    private static byte[] validHeaderRomWithGameCode(String gameCode) {
        byte[] rom = new byte[0x200];
        byte[] bytes = gameCode.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, rom, 0xAC, bytes.length);
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }
}
