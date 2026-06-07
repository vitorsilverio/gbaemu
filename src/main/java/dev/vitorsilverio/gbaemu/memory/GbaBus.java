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
    // O(1) dispatch: per high-byte (address >>> 24) cache of the last space that
    // handled that block. The GBA map is fixed in the top bits, so after the first
    // access a region resolves in one contains() check instead of scanning every space.
    private final MemorySpace[] regionCache = new MemorySpace[256];
    private MemorySpace waitcntSpace;
    private int openBusValue;

    public void add(MemorySpace space) {
        spaces.add(space);
        // Registration happens during setup; drop the derived caches so they rebuild.
        java.util.Arrays.fill(regionCache, null);
        waitcntSpace = null;
    }

    /// Resolves the space owning an address, caching the result per high byte. Falls
    /// back to a full scan on a miss (e.g. the I/O block, which holds several devices),
    /// preserving the exact match order of the registered spaces.
    private MemorySpace spaceFor(int address) {
        int index = address >>> 24;
        MemorySpace cached = regionCache[index];
        if (cached != null && cached.contains(address)) {
            return cached;
        }
        for (MemorySpace space : spaces) {
            if (space.contains(address)) {
                regionCache[index] = space;
                return space;
            }
        }
        return null;
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
        MemorySpace space = spaceFor(address);
        if (space != null) {
            return space.readByte(address) & 0xFF;
        }
        return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
    }

    @Override
    public int read16(int address) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // SRAM/Flash sit on an 8-bit bus: a 16-bit read fetches one byte and
            // mirrors it across both lanes (byte * 0x0101).
            int b = read8(address & ~1) & 0xFF;
            return b | (b << 8);
        }
        int aligned = address & ~1;
        MemorySpace space = spaceFor(aligned);
        if (space != null) {
            return space.readHalfWord(aligned) & 0xFFFF;
        }
        return (openBusValue >>> ((aligned & 2) * 8)) & 0xFFFF;
    }

    @Override
    public int read32(int address) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: a 32-bit read mirrors the single byte across all lanes.
            int b = read8(address & ~3) & 0xFF;
            return b * 0x01010101;
        }
        int aligned = address & ~3;
        MemorySpace space = spaceFor(aligned);
        if (space != null) {
            int value = space.readWord(aligned);
            return Integer.rotateRight(value, (address & 3) * 8);
        }
        return openBusValue;
    }

    @Override
    public void write8(int address, int value) {
        MemorySpace space = spaceFor(address);
        if (space != null) {
            space.writeByte(address, value & 0xFF);
        }
    }

    @Override
    public void write16(int address, int value) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: only the byte facing the chip is written, selected by the
            // low address bits (value rotated right by 8*(addr&3)).
            write8(address, value >>> (8 * (address & 3)));
            return;
        }
        int aligned = address & ~1;
        MemorySpace space = spaceFor(aligned);
        if (space != null) {
            space.writeHalfWord(aligned, value & 0xFFFF);
        }
    }

    @Override
    public void write32(int address, int value) {
        if (GbaMemoryRegion.SRAM.contains(address)) {
            // 8-bit bus: a 32-bit store also writes a single byte (the lane facing
            // the chip), not all four.
            write8(address, value >>> (8 * (address & 3)));
            return;
        }
        int aligned = address & ~3;
        MemorySpace space = spaceFor(aligned);
        if (space != null) {
            space.writeWord(aligned, value);
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

    /// WAITCNT is read on every cartridge access to derive wait states; resolve the
    /// owning space once and read it directly instead of scanning the bus each time.
    private int readWaitcnt() {
        MemorySpace space = waitcntSpace;
        if (space == null) {
            for (MemorySpace candidate : spaces) {
                if (candidate.contains(WAITCNT)) {
                    space = candidate;
                    break;
                }
            }
            waitcntSpace = space;
        }
        return space != null ? space.readHalfWord(WAITCNT) & 0xFFFF : 0;
    }
}
