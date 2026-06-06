package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Registradores LCD e temporizador do LCD do GBA.
///
/// Possui todos os registradores de IO LCD (0x04000000-0x0400005F):
/// DISPCNT, DISPSTAT, VCOUNT, BG0-3 CNT/OFS, parametros affine e janelas.
/// A logica de timing atualiza DISPSTAT e VCOUNT internamente.
public final class GbaLcdTiming implements MemorySpace {
    public static final int CYCLES_PER_SCANLINE = 1232;
    public static final int VISIBLE_SCANLINES   = 160;
    public static final int TOTAL_SCANLINES     = 228;

    public static final int LCD_START = 0x04000000;
    public static final int LCD_END   = 0x0400005F;

    private static final int DISPSTAT     = 0x04000004;
    private static final int VCOUNT       = 0x04000006;
    private static final int VBLANK_FLAG  = 1;
    private static final int HBLANK_FLAG  = 1 << 1;
    private static final int VCOUNT_FLAG  = 1 << 2;
    private static final int VCOUNT_SETTING_SHIFT = 8;
    private static final int HBLANK_START_CYCLE   = 960;

    private static final int REGISTERS_SIZE = LCD_END - LCD_START + 1;

    private final byte[] registers = new byte[REGISTERS_SIZE];
    private final GbaInterruptController interrupts;

    private int scanline;
    private int scanlineCycles;
    private boolean vblank;
    private boolean hblank;
    private boolean vcountMatch;

    public record Events(int vblankStartedCount, int hblankStartedCount, int vcountMatchedCount) {
        static final Events NONE = new Events(0, 0, 0);

        public boolean vblankStarted() { return vblankStartedCount > 0; }
        public boolean hblankStarted() { return hblankStartedCount > 0; }
        public boolean vcountMatched() { return vcountMatchedCount > 0; }

        private Events plus(Events other) {
            return new Events(
                    vblankStartedCount + other.vblankStartedCount,
                    hblankStartedCount + other.hblankStartedCount,
                    vcountMatchedCount + other.vcountMatchedCount);
        }
    }

    public GbaLcdTiming(GbaInterruptController interrupts) {
        this.interrupts = interrupts;
        updateRegisters();
    }

    @Override
    public boolean contains(int address) {
        return address >= LCD_START && address <= LCD_END;
    }

    @Override
    public int readByte(int address) {
        return registers[address - LCD_START] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        if (address == DISPSTAT) {
            // bits 0-2 are hardware-only status flags; bits 3-7 are game-writable
            int current = registers[DISPSTAT - LCD_START] & 0xFF;
            registers[DISPSTAT - LCD_START] = (byte) ((current & 0x07) | (value & 0xF8));
        } else if (address == VCOUNT || address == VCOUNT + 1) {
            // read-only from game perspective — ignore
        } else {
            registers[address - LCD_START] = (byte) value;
        }
    }

    public int scanline() { return scanline; }

    public int scanlineCycles() { return scanlineCycles; }

    public Events tick(int cycles) {
        if (cycles < 0) throw new IllegalArgumentException("cycles must be >= 0");
        if (cycles == 0) return Events.NONE;

        Events events = Events.NONE;
        int remaining = cycles;
        while (remaining > 0) {
            int untilScanlineEnd = CYCLES_PER_SCANLINE - scanlineCycles;
            int untilNextEvent   = untilScanlineEnd;
            if (scanlineCycles < HBLANK_START_CYCLE) {
                untilNextEvent = Math.min(untilNextEvent, HBLANK_START_CYCLE - scanlineCycles);
            }
            int step = Math.min(remaining, untilNextEvent);
            scanlineCycles += step;
            remaining -= step;

            if (step == untilNextEvent) {
                events = events.plus(updateRegisters());
                if (scanlineCycles == CYCLES_PER_SCANLINE) {
                    scanlineCycles = 0;
                    scanline++;
                    if (scanline == TOTAL_SCANLINES) scanline = 0;
                    events = events.plus(updateRegisters());
                }
            }
        }
        events = events.plus(updateRegisters());
        return events;
    }

    private Events updateRegisters() {
        int dispstat = rawRead16(DISPSTAT) & 0xFFF8;
        boolean nextVblank      = scanline >= VISIBLE_SCANLINES;
        boolean nextHblank      = scanlineCycles >= HBLANK_START_CYCLE;
        boolean nextVcountMatch = scanline == ((dispstat >>> VCOUNT_SETTING_SHIFT) & 0xFF);

        if (nextVblank)      dispstat |= VBLANK_FLAG;
        if (nextHblank)      dispstat |= HBLANK_FLAG;
        if (nextVcountMatch) dispstat |= VCOUNT_FLAG;

        Events events = eventsOnRisingEdges(dispstat, nextVblank, nextHblank, nextVcountMatch);
        vblank      = nextVblank;
        hblank      = nextHblank;
        vcountMatch = nextVcountMatch;

        rawWrite16(DISPSTAT, dispstat);
        rawWrite16(VCOUNT, scanline);
        return events;
    }

    private Events eventsOnRisingEdges(int dispstat, boolean nextVblank, boolean nextHblank, boolean nextVcountMatch) {
        boolean vblankStarted  = !vblank      && nextVblank;
        boolean hblankStarted  = !hblank      && nextHblank;
        boolean vcountMatched  = !vcountMatch && nextVcountMatch;
        if (interrupts != null) {
            if (vblankStarted  && (dispstat & (1 << 3)) != 0) interrupts.request(GbaInterrupt.VBLANK);
            if (hblankStarted  && (dispstat & (1 << 4)) != 0) interrupts.request(GbaInterrupt.HBLANK);
            if (vcountMatched  && (dispstat & (1 << 5)) != 0) interrupts.request(GbaInterrupt.VCOUNT);
        }
        return new Events(vblankStarted ? 1 : 0, hblankStarted ? 1 : 0, vcountMatched ? 1 : 0);
    }

    // Raw register access (hardware writes, no restrictions)
    private int rawRead16(int address) {
        int offset = address - LCD_START;
        return (registers[offset] & 0xFF) | ((registers[offset + 1] & 0xFF) << 8);
    }

    private void rawWrite16(int address, int value) {
        int offset = address - LCD_START;
        registers[offset]     = (byte) value;
        registers[offset + 1] = (byte) (value >>> 8);
    }
}
