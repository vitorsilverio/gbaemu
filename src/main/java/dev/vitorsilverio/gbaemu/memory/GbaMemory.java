package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Arrays;
import java.util.Objects;

/// Barramento inicial do GBA, conectado ao contrato `AddressSpace` do arm-jitter.
public final class GbaMemory implements AddressSpace {
    private static final int IF = 0x04000202;

    public static final int BIOS_SIZE = 16 * 1024;
    public static final int EWRAM_SIZE = 256 * 1024;
    public static final int IWRAM_SIZE = 32 * 1024;
    public static final int IO_SIZE = 1024;
    public static final int PALETTE_SIZE = 1024;
    public static final int VRAM_SIZE = 96 * 1024;
    public static final int OAM_SIZE = 1024;
    public static final int MAX_ROM_SIZE = 32 * 1024 * 1024;
    public static final int SRAM_SIZE = 64 * 1024;

    private final byte[] bios;
    private final byte[] ewram = new byte[EWRAM_SIZE];
    private final byte[] iwram = new byte[IWRAM_SIZE];
    private final byte[] io = new byte[IO_SIZE];
    private final byte[] palette = new byte[PALETTE_SIZE];
    private final byte[] vram = new byte[VRAM_SIZE];
    private final byte[] oam = new byte[OAM_SIZE];
    private final byte[] rom;
    private final byte[] sram = new byte[SRAM_SIZE];
    private int openBusValue;

    private GbaMemory(byte[] bios, byte[] rom) {
        this.bios = Arrays.copyOf(Objects.requireNonNull(bios, "bios"), BIOS_SIZE);
        this.rom = Arrays.copyOf(Objects.requireNonNull(rom, "rom"), Math.min(rom.length, MAX_ROM_SIZE));
        Arrays.fill(sram, (byte) 0xFF);
    }

    public static GbaMemory withoutBios(byte[] rom) {
        return new GbaMemory(new byte[BIOS_SIZE], rom);
    }

    public static GbaMemory withBios(byte[] bios, byte[] rom) {
        return new GbaMemory(bios, rom);
    }

    public int openBusValue() {
        return openBusValue;
    }

    public void setOpenBusValue(int openBusValue) {
        this.openBusValue = openBusValue;
    }

    @Override
    public int read8(int address) {
        GbaMemoryRegion region = regionFor(address);
        if (region == null) {
            return openBus8(address);
        }

        return switch (region) {
            case BIOS -> bios[region.offset(address)] & 0xFF;
            case EWRAM -> ewram[region.offset(address)] & 0xFF;
            case IWRAM -> iwram[region.offset(address)] & 0xFF;
            case IO -> io[region.offset(address)] & 0xFF;
            case PALETTE -> palette[region.offset(address)] & 0xFF;
            case VRAM -> vram[vramOffset(address)] & 0xFF;
            case OAM -> oam[region.offset(address)] & 0xFF;
            case GAME_PAK_WS0, GAME_PAK_WS1, GAME_PAK_WS2 -> readRom8(region.offset(address));
            case SRAM -> sram[region.offset(address)] & 0xFF;
        };
    }

    @Override
    public int read16(int address) {
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(int address) {
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(int address, int value) {
        GbaMemoryRegion region = regionFor(address);
        int data = value & 0xFF;
        if (region == null || region == GbaMemoryRegion.BIOS
                || region == GbaMemoryRegion.GAME_PAK_WS0
                || region == GbaMemoryRegion.GAME_PAK_WS1
                || region == GbaMemoryRegion.GAME_PAK_WS2) {
            return;
        }

        switch (region) {
            case EWRAM -> ewram[region.offset(address)] = (byte) data;
            case IWRAM -> iwram[region.offset(address)] = (byte) data;
            case IO -> io[region.offset(address)] = (byte) data;
            case PALETTE -> writeMirroredHalfwordByte(palette, region.offset(address), data);
            case VRAM -> writeVram8(address, data);
            case OAM -> {
            }
            case SRAM -> sram[region.offset(address)] = (byte) data;
            default -> {
            }
        }
    }

    @Override
    public void write16(int address, int value) {
        if ((address & ~1) == IF) {
            int offset = GbaMemoryRegion.IO.offset(IF);
            int current = (io[offset] & 0xFF) | ((io[offset + 1] & 0xFF) << 8);
            int next = current & ~(value & 0x3FFF);
            io[offset] = (byte) next;
            io[offset + 1] = (byte) (next >>> 8);
            return;
        }
        writePhysical8(address, value);
        writePhysical8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    private void writePhysical8(int address, int value) {
        GbaMemoryRegion region = regionFor(address);
        int data = value & 0xFF;
        if (region == null || region == GbaMemoryRegion.BIOS
                || region == GbaMemoryRegion.GAME_PAK_WS0
                || region == GbaMemoryRegion.GAME_PAK_WS1
                || region == GbaMemoryRegion.GAME_PAK_WS2) {
            return;
        }

        switch (region) {
            case EWRAM -> ewram[region.offset(address)] = (byte) data;
            case IWRAM -> iwram[region.offset(address)] = (byte) data;
            case IO -> io[region.offset(address)] = (byte) data;
            case PALETTE -> palette[region.offset(address)] = (byte) data;
            case VRAM -> vram[vramOffset(address)] = (byte) data;
            case OAM -> oam[region.offset(address)] = (byte) data;
            case SRAM -> sram[region.offset(address)] = (byte) data;
            default -> {
            }
        }
    }

    private int readRom8(int offset) {
        if (rom.length == 0 || offset >= rom.length) {
            return openBus8(GbaMemoryRegion.GAME_PAK_WS0.start() + offset);
        }
        return rom[offset] & 0xFF;
    }

    private int openBus8(int address) {
        return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
    }

    private int vramOffset(int address) {
        int offset = GbaMemoryRegion.VRAM.offset(address);
        if (offset >= VRAM_SIZE) {
            return 0x10000 + ((offset - VRAM_SIZE) & 0x7FFF);
        }
        return offset;
    }

    private void writeVram8(int address, int data) {
        int offset = vramOffset(address);
        if (offset >= objVramStart()) {
            return;
        }
        writeMirroredHalfwordByte(vram, offset, data);
    }

    private int objVramStart() {
        int mode = io[0] & 0x7;
        return mode >= 3 && mode <= 5 ? 0x14000 : 0x10000;
    }

    private static void writeMirroredHalfwordByte(byte[] target, int offset, int data) {
        int aligned = offset & ~1;
        target[aligned] = (byte) data;
        target[aligned + 1] = (byte) data;
    }

    private static GbaMemoryRegion regionFor(int address) {
        for (GbaMemoryRegion region : GbaMemoryRegion.values()) {
            if (region.contains(address)) {
                return region;
            }
        }
        return null;
    }
}
