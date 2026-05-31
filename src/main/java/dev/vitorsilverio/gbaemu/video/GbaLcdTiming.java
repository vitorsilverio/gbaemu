package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Temporizador inicial do LCD do GBA.
///
/// Mantem VCOUNT/DISPSTAT coerentes o bastante para o loop de boot e para
/// testes de espera por VBlank/HBlank.
public final class GbaLcdTiming {
    public static final int CYCLES_PER_SCANLINE = 1232;
    public static final int VISIBLE_SCANLINES = 160;
    public static final int TOTAL_SCANLINES = 228;

    private static final int DISPSTAT = 0x04000004;
    private static final int VCOUNT = 0x04000006;
    private static final int VBLANK_FLAG = 1;
    private static final int HBLANK_FLAG = 1 << 1;
    private static final int VCOUNT_FLAG = 1 << 2;
    private static final int VCOUNT_SETTING_SHIFT = 8;
    private static final int HBLANK_START_CYCLE = 960;

    private final AddressSpace memory;
    private final GbaInterruptController interrupts;
    private int scanline;
    private int scanlineCycles;
    private boolean vblank;
    private boolean hblank;
    private boolean vcountMatch;

    public GbaLcdTiming(AddressSpace memory) {
        this(memory, null);
    }

    public GbaLcdTiming(AddressSpace memory, GbaInterruptController interrupts) {
        this.memory = memory;
        this.interrupts = interrupts;
        updateRegisters();
    }

    public int scanline() {
        return scanline;
    }

    public int scanlineCycles() {
        return scanlineCycles;
    }

    public void tick(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be >= 0");
        }

        scanlineCycles += cycles;
        while (scanlineCycles >= CYCLES_PER_SCANLINE) {
            scanlineCycles -= CYCLES_PER_SCANLINE;
            scanline++;
            if (scanline == TOTAL_SCANLINES) {
                scanline = 0;
            }
        }
        updateRegisters();
    }

    private void updateRegisters() {
        int dispstat = memory.read16(DISPSTAT) & 0xFFF8;
        boolean nextVblank = scanline >= VISIBLE_SCANLINES;
        boolean nextHblank = scanlineCycles >= HBLANK_START_CYCLE;
        boolean nextVcountMatch = scanline == ((dispstat >>> VCOUNT_SETTING_SHIFT) & 0xFF);

        if (nextVblank) {
            dispstat |= VBLANK_FLAG;
        }
        if (nextHblank) {
            dispstat |= HBLANK_FLAG;
        }
        if (nextVcountMatch) {
            dispstat |= VCOUNT_FLAG;
        }

        requestInterruptsOnRisingEdges(dispstat, nextVblank, nextHblank, nextVcountMatch);
        vblank = nextVblank;
        hblank = nextHblank;
        vcountMatch = nextVcountMatch;

        memory.write16(DISPSTAT, dispstat);
        memory.write16(VCOUNT, scanline);
    }

    private void requestInterruptsOnRisingEdges(
            int dispstat,
            boolean nextVblank,
            boolean nextHblank,
            boolean nextVcountMatch) {
        if (interrupts == null) {
            return;
        }
        if (!vblank && nextVblank && (dispstat & (1 << 3)) != 0) {
            interrupts.request(GbaInterrupt.VBLANK);
        }
        if (!hblank && nextHblank && (dispstat & (1 << 4)) != 0) {
            interrupts.request(GbaInterrupt.HBLANK);
        }
        if (!vcountMatch && nextVcountMatch && (dispstat & (1 << 5)) != 0) {
            interrupts.request(GbaInterrupt.VCOUNT);
        }
    }
}
