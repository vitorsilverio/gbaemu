package dev.vitorsilverio.gbaemu.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class GbaBus implements AddressSpace {

    private static final int WAITCNT = 0x04000204;

    private final List<MemorySpace> spaces = new ArrayList<>();
    private int openBusValue;

    public void add(MemorySpace space) {
        spaces.add(space);
    }

    public <T extends MemorySpace> Optional<T> find(Class<T> type) {
        return spaces.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    public int openBusValue() {
        return openBusValue;
    }

    public void setOpenBusValue(int value) {
        this.openBusValue = value;
    }

    @Override
    public int read8(int address) {
        for (MemorySpace space : spaces) {
            if (space.contains(address)) {
                return space.readByte(address) & 0xFF;
            }
        }
        return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
    }

    @Override
    public int read16(int address) {
        int aligned = address & ~1;
        for (MemorySpace space : spaces) {
            if (space.contains(aligned)) {
                return space.readHalfWord(aligned) & 0xFFFF;
            }
        }
        return (openBusValue >>> ((aligned & 2) * 8)) & 0xFFFF;
    }

    @Override
    public int read32(int address) {
        int aligned = address & ~3;
        for (MemorySpace space : spaces) {
            if (space.contains(aligned)) {
                int value = space.readWord(aligned);
                return Integer.rotateRight(value, (address & 3) * 8);
            }
        }
        return openBusValue;
    }

    @Override
    public void write8(int address, int value) {
        for (MemorySpace space : spaces) {
            if (space.contains(address)) {
                space.writeByte(address, value & 0xFF);
                return;
            }
        }
    }

    @Override
    public void write16(int address, int value) {
        int aligned = address & ~1;
        for (MemorySpace space : spaces) {
            if (space.contains(aligned)) {
                space.writeHalfWord(aligned, value & 0xFFFF);
                return;
            }
        }
    }

    @Override
    public void write32(int address, int value) {
        int aligned = address & ~3;
        for (MemorySpace space : spaces) {
            if (space.contains(aligned)) {
                space.writeWord(aligned, value);
                return;
            }
        }
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        GbaMemoryRegion region = GbaMemoryRegion.regionFor(address);
        if (region == null) return 0;
        int cycles = switch (region) {
            case EWRAM -> 3;
            case GAME_PAK_WS0, GAME_PAK_WS1, GAME_PAK_WS2 -> gamePakCycles(region, sizeBytes);
            case SRAM -> 5;
            default -> 1;
        };
        return Math.max(0, cycles - 1);
    }

    private int gamePakCycles(GbaMemoryRegion region, int sizeBytes) {
        int waitcnt = read16(WAITCNT);
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
}
