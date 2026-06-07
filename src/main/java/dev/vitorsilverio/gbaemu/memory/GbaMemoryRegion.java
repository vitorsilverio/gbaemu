package dev.vitorsilverio.gbaemu.memory;

/// Regioes principais do mapa de memoria do Game Boy Advance.
///
/// Os intervalos seguem a tabela "GBA Memory Map" do GBATEK. As regioes com
/// espelhamento usam `mirrorSize` para converter o endereco de CPU em offset
/// dentro do bloco fisico correspondente.
public enum GbaMemoryRegion {
    BIOS(0x00000000, 0x00003FFF, 0x4000),
    EWRAM(0x02000000, 0x02FFFFFF, 0x40000),
    IWRAM(0x03000000, 0x03FFFFFF, 0x8000),
    IO(0x04000000, 0x040003FE, 0x400),
    PALETTE(0x05000000, 0x05FFFFFF, 0x400),
    VRAM(0x06000000, 0x06FFFFFF, 0x20000),
    OAM(0x07000000, 0x07FFFFFF, 0x400),
    GAME_PAK_WS0(0x08000000, 0x09FFFFFF, 0x2000000),
    GAME_PAK_WS1(0x0A000000, 0x0BFFFFFF, 0x2000000),
    GAME_PAK_WS2(0x0C000000, 0x0DFFFFFF, 0x2000000),
    SRAM(0x0E000000, 0x0FFFFFFF, 0x10000);

    private final int start;
    private final int end;
    private final int mirrorSize;

    GbaMemoryRegion(int start, int end, int mirrorSize) {
        this.start = start;
        this.end = end;
        this.mirrorSize = mirrorSize;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public int mirrorSize() {
        return mirrorSize;
    }

    public boolean contains(int address) {
        int unsignedCompareStart = Integer.compareUnsigned(address, start);
        int unsignedCompareEnd = Integer.compareUnsigned(address, end);
        return unsignedCompareStart >= 0 && unsignedCompareEnd <= 0;
    }

    public int offset(int address) {
        return Math.floorMod(address - start, mirrorSize);
    }

    // O(1) lookup by the high byte of the address (the GBA map is fixed in bits 24-31).
    // The high byte alone over-approximates regions that do not fill their whole block
    // (BIOS, IO), so the resolved region is still verified with contains().
    private static final GbaMemoryRegion[] BY_HIGH_BYTE = new GbaMemoryRegion[256];

    static {
        for (GbaMemoryRegion region : values()) {
            int firstByte = region.start >>> 24;
            int lastByte = region.end >>> 24;
            for (int highByte = firstByte; highByte <= lastByte; highByte++) {
                BY_HIGH_BYTE[highByte] = region;
            }
        }
    }

    public static GbaMemoryRegion regionFor(int address) {
        GbaMemoryRegion region = BY_HIGH_BYTE[address >>> 24];
        return (region != null && region.contains(address)) ? region : null;
    }
}
