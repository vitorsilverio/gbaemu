package dev.vitorsilverio.gbaemu.cartridge;

import java.util.Arrays;

/// Cartucho GBA carregado em memoria.
public final class GbaCartridge {
    private final byte[] rom;
    private final GbaCartridgeHeader header;
    private final GbaSaveType saveType;

    private GbaCartridge(byte[] rom, GbaCartridgeHeader header, GbaSaveType saveType) {
        this.rom = rom;
        this.header = header;
        this.saveType = saveType;
    }

    public static GbaCartridge load(byte[] rom) {
        byte[] copy = Arrays.copyOf(rom, rom.length);
        return new GbaCartridge(copy, GbaCartridgeHeader.parse(copy), GbaSaveTypeDetector.detect(copy));
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
}
