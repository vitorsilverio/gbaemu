package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveMemory;

import java.util.Arrays;
import java.util.Objects;

/// Barramento inicial do GBA, conectado ao contrato `AddressSpace` do arm-jitter.
public final class GbaMemory implements AddressSpace {
    private static final int IF = 0x04000202;
    private static final int WAITCNT = 0x04000204;

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
    private final GbaAudio audio = new GbaAudio(io);
    private final byte[] palette = new byte[PALETTE_SIZE];
    private final byte[] vram = new byte[VRAM_SIZE];
    private final byte[] oam = new byte[OAM_SIZE];
    private final byte[] rom;
    private final GbaSaveMemory saveMemory;
    private int openBusValue;

    private GbaMemory(byte[] bios, byte[] rom, GbaSaveMemory saveMemory) {
        this.bios = Arrays.copyOf(Objects.requireNonNull(bios, "bios"), BIOS_SIZE);
        this.rom = Arrays.copyOf(Objects.requireNonNull(rom, "rom"), Math.min(rom.length, MAX_ROM_SIZE));
        this.saveMemory = Objects.requireNonNull(saveMemory, "saveMemory");
    }

    public static GbaMemory withoutBios(byte[] rom) {
        return new GbaMemory(skipBiosStub(), rom, GbaSaveMemory.forType(dev.vitorsilverio.gbaemu.cartridge.GbaSaveType.SRAM));
    }

    public static GbaMemory withBios(byte[] bios, byte[] rom) {
        return new GbaMemory(bios, rom, GbaSaveMemory.forType(dev.vitorsilverio.gbaemu.cartridge.GbaSaveType.SRAM));
    }

    public static GbaMemory withoutBios(byte[] rom, GbaSaveMemory saveMemory) {
        return new GbaMemory(skipBiosStub(), rom, saveMemory);
    }

    public static GbaMemory withBios(byte[] bios, byte[] rom, GbaSaveMemory saveMemory) {
        return new GbaMemory(bios, rom, saveMemory);
    }

    public static GbaMemory withBiosHle(byte[] bios, byte[] rom, GbaSaveMemory saveMemory) {
        byte[] hleBios = Arrays.copyOf(bios, BIOS_SIZE);
        byte[] stub = skipBiosStub();
        System.arraycopy(stub, 0x18, hleBios, 0x18, 0x2C);
        return new GbaMemory(hleBios, rom, saveMemory);
    }

    public GbaSaveMemory saveMemory() {
        return saveMemory;
    }

    public GbaAudio audio() {
        return audio;
    }

    public void requestInterruptFlags(int mask) {
        int next = readInterruptFlags() | (mask & 0x3FFF);
        writeInterruptFlagsRaw(next);
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
            case IO -> audio.handles(address) ? audio.read8(address) : io[region.offset(address)] & 0xFF;
            case PALETTE -> palette[region.offset(address)] & 0xFF;
            case VRAM -> vram[vramOffset(address)] & 0xFF;
            case OAM -> oam[region.offset(address)] & 0xFF;
            case GAME_PAK_WS0, GAME_PAK_WS1, GAME_PAK_WS2 -> readRom8(region.offset(address));
            case SRAM -> saveMemory.read8(region.offset(address));
        };
    }

    @Override
    public int read16(int address) {
        int aligned = address & ~1;
        return read8(aligned) | (read8(aligned + 1) << 8);
    }

    @Override
    public int read32(int address) {
        int aligned = address & ~3;
        int value = read16(aligned) | (read16(aligned + 2) << 16);
        return Integer.rotateRight(value, (address & 3) * 8);
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
            case IO -> {
                if (audio.handles(address)) {
                    audio.write8(address, data);
                } else if (address == IF) {
                    clearInterruptFlags(data);
                } else if (address == IF + 1) {
                    clearInterruptFlags(data << 8);
                } else {
                    io[region.offset(address)] = (byte) data;
                }
            }
            case PALETTE -> writeMirroredHalfwordByte(palette, region.offset(address), data);
            case VRAM -> writeVram8(address, data);
            case OAM -> {
            }
            case SRAM -> saveMemory.write8(region.offset(address), data);
            default -> {
            }
        }
    }

    @Override
    public void write16(int address, int value) {
        int aligned = address & ~1;
        if (audio.handles(aligned)) {
            audio.write16(aligned, value);
            return;
        }
        if (aligned == IF) {
            clearInterruptFlags(value);
            return;
        }
        writePhysical8(aligned, value);
        writePhysical8(aligned + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        int aligned = address & ~3;
        if (audio.handles(aligned)) {
            audio.write32(aligned, value);
            return;
        }
        write16(aligned, value);
        write16(aligned + 2, value >>> 16);
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        GbaMemoryRegion region = regionFor(address);
        if (region == null) {
            return 0;
        }
        int cycles = switch (region) {
            case EWRAM -> 3;
            case GAME_PAK_WS0, GAME_PAK_WS1, GAME_PAK_WS2 -> gamePakCycles(region, sizeBytes);
            case SRAM -> 5;
            default -> 1;
        };
        return Math.max(0, cycles - 1);
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
            case IO -> {
                if (audio.handles(address)) {
                    audio.write8(address, data);
                } else {
                    io[region.offset(address)] = (byte) data;
                }
            }
            case PALETTE -> palette[region.offset(address)] = (byte) data;
            case VRAM -> vram[vramOffset(address)] = (byte) data;
            case OAM -> oam[region.offset(address)] = (byte) data;
            case SRAM -> saveMemory.write8(region.offset(address), data);
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

    private int readInterruptFlags() {
        int offset = GbaMemoryRegion.IO.offset(IF);
        return (io[offset] & 0xFF) | ((io[offset + 1] & 0xFF) << 8);
    }

    private void clearInterruptFlags(int mask) {
        writeInterruptFlagsRaw(readInterruptFlags() & ~(mask & 0x3FFF));
    }

    private void writeInterruptFlagsRaw(int value) {
        int next = value & 0x3FFF;
        int offset = GbaMemoryRegion.IO.offset(IF);
        io[offset] = (byte) next;
        io[offset + 1] = (byte) (next >>> 8);
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

    private int gamePakCycles(GbaMemoryRegion region, int sizeBytes) {
        int waitcnt = readWaitcnt();
        int waitstate = switch (region) {
            case GAME_PAK_WS0 -> (waitcnt >>> 2) & 0x3;
            case GAME_PAK_WS1 -> (waitcnt >>> 5) & 0x3;
            case GAME_PAK_WS2 -> (waitcnt >>> 8) & 0x3;
            default -> 0;
        };
        int cycles = switch (waitstate) {
            case 0 -> 4;
            case 1 -> 3;
            case 2 -> 2;
            case 3 -> 8;
            default -> throw new IllegalStateException("Invalid waitstate: " + waitstate);
        };
        return sizeBytes == 4 ? cycles * 2 : cycles;
    }

    private int readWaitcnt() {
        int offset = GbaMemoryRegion.IO.offset(WAITCNT);
        return (io[offset] & 0xFF) | ((io[offset + 1] & 0xFF) << 8);
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

    private static byte[] skipBiosStub() {
        byte[] stub = new byte[BIOS_SIZE];
        write32(stub, 0x18, 0xE92D500F); // stmdb sp!, {r0-r3,r12,lr}
        write32(stub, 0x1C, 0xE59F001C); // ldr r0, [pc, #0x1c]
        write32(stub, 0x20, 0xE5900000); // ldr r0, [r0]
        write32(stub, 0x24, 0xE3500000); // cmp r0, #0
        write32(stub, 0x28, 0x0A000001); // beq restore
        write32(stub, 0x2C, 0xE1A0E00F); // mov lr, pc
        write32(stub, 0x30, 0xE12FFF10); // bx r0
        write32(stub, 0x34, 0xE8BD500F); // ldmia sp!, {r0-r3,r12,lr}
        write32(stub, 0x38, 0xE25EF004); // subs pc, lr, #4
        write32(stub, 0x40, 0x03007FFC);
        return stub;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
