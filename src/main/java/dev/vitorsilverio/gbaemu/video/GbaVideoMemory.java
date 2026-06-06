package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.memory.GbaMemoryRegion;

import java.util.function.IntSupplier;

/// Memoria de video do GBA: Palette RAM, VRAM e OAM agrupados como um unico
/// espaco de enderecamento que o bus ve como um dispositivo.
public final class GbaVideoMemory implements MemorySpace {
    private final GbaPalette palette = new GbaPalette();
    private final GbaVram vram;
    private final GbaOam oam = new GbaOam();

    public GbaVideoMemory(IntSupplier displayMode) {
        this.vram = new GbaVram(displayMode);
    }

    @Override
    public boolean contains(int address) {
        return GbaMemoryRegion.PALETTE.contains(address)
                || GbaMemoryRegion.VRAM.contains(address)
                || GbaMemoryRegion.OAM.contains(address);
    }

    @Override
    public int readByte(int address) {
        if (GbaMemoryRegion.PALETTE.contains(address)) return palette.readByte(address);
        if (GbaMemoryRegion.VRAM.contains(address))    return vram.readByte(address);
        return oam.readByte(address);
    }

    @Override
    public int readHalfWord(int address) {
        if (GbaMemoryRegion.PALETTE.contains(address)) return palette.readHalfWord(address);
        if (GbaMemoryRegion.VRAM.contains(address))    return vram.readHalfWord(address);
        return oam.readHalfWord(address);
    }

    @Override
    public int readWord(int address) {
        if (GbaMemoryRegion.PALETTE.contains(address)) return palette.readWord(address);
        if (GbaMemoryRegion.VRAM.contains(address))    return vram.readWord(address);
        return oam.readWord(address);
    }

    @Override
    public void writeByte(int address, int value) {
        if (GbaMemoryRegion.PALETTE.contains(address)) { palette.writeByte(address, value); return; }
        if (GbaMemoryRegion.VRAM.contains(address))    { vram.writeByte(address, value); return; }
        oam.writeByte(address, value);
    }

    @Override
    public void writeHalfWord(int address, int value) {
        if (GbaMemoryRegion.PALETTE.contains(address)) { palette.writeHalfWord(address, value); return; }
        if (GbaMemoryRegion.VRAM.contains(address))    { vram.writeHalfWord(address, value); return; }
        oam.writeHalfWord(address, value);
    }

    @Override
    public void writeWord(int address, int value) {
        if (GbaMemoryRegion.PALETTE.contains(address)) { palette.writeWord(address, value); return; }
        if (GbaMemoryRegion.VRAM.contains(address))    { vram.writeWord(address, value); return; }
        oam.writeWord(address, value);
    }
}
