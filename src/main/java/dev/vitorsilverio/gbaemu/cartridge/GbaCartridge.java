package dev.vitorsilverio.gbaemu.cartridge;

import java.util.Arrays;

/// Cartucho GBA carregado em memoria.
public final class GbaCartridge {
    private final byte[] rom;
    private final GbaCartridgeHeader header;
    private final GbaSaveType saveType;
    private final boolean rtc;

    private GbaCartridge(byte[] rom, GbaCartridgeHeader header, GbaSaveType saveType, boolean rtc) {
        this.rom = rom;
        this.header = header;
        this.saveType = saveType;
        this.rtc = rtc;
    }

    public static GbaCartridge load(byte[] rom) {
        byte[] copy = Arrays.copyOf(rom, rom.length);
        GbaCartridgeHeader header = GbaCartridgeHeader.parse(copy);
        return new GbaCartridge(copy, header, GbaSaveTypeDetector.detect(copy), GbaRtcDetector.hasRtc(header.gameCode()));
    }

    public byte[] rom() {
        return Arrays.copyOf(rom, rom.length);
    }

    public GbaCartridgeHeader header() {
        return header;
    }

    public GbaSaveType saveType() {
        return saveType;
    }

    /// GBATEK "GBA Cart Real-Time Clock (RTC)" — chip S-3511A por GPIO (task D1).
    public boolean hasRtc() {
        return rtc;
    }
}
