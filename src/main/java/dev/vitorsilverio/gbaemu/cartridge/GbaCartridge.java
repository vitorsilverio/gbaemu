package dev.vitorsilverio.gbaemu.cartridge;

import java.util.Arrays;

/// Cartucho GBA carregado em memoria.
public final class GbaCartridge {
    private final byte[] rom;
    private final GbaCartridgeHeader header;

    private GbaCartridge(byte[] rom, GbaCartridgeHeader header) {
        this.rom = rom;
        this.header = header;
    }

    public static GbaCartridge load(byte[] rom) {
        byte[] copy = Arrays.copyOf(rom, rom.length);
        return new GbaCartridge(copy, GbaCartridgeHeader.parse(copy));
    }

    public byte[] rom() {
        return Arrays.copyOf(rom, rom.length);
    }

    public GbaCartridgeHeader header() {
        return header;
    }
}
