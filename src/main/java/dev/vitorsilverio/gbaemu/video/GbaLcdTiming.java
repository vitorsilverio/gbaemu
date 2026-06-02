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

    public record Events(int vblankStartedCount, int hblankStartedCount, int vcountMatchedCount) {
        static final Events NONE = new Events(0, 0, 0);

        public boolean vblankStarted() {
            return vblankStartedCount > 0;
        }

        public boolean hblankStarted() {
            return hblankStartedCount > 0;
        }

        public boolean vcountMatched() {
            return vcountMatchedCount > 0;
        }

        private Events plus(Events other) {
            return new Events(
                    vblankStartedCount + other.vblankStartedCount,
                    hblankStartedCount + other.hblankStartedCount,
                    vcountMatchedCount + other.vcountMatchedCount);
        }
    }

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

    public Events tick(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be >= 0");
        }
        if (cycles == 0) {
            return Events.NONE;
        }

        Events events = Events.NONE;
        int remaining = cycles;
        while (remaining > 0) {
            int untilScanlineEnd = CYCLES_PER_SCANLINE - scanlineCycles;
            int untilNextEvent = untilScanlineEnd;
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
                    if (scanline == TOTAL_SCANLINES) {
                        scanline = 0;
                    }
                    events = events.plus(updateRegisters());
                }
            }
        }
        events = events.plus(updateRegisters());
        return events;
    }

    private Events updateRegisters() {
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

        Events events = eventsOnRisingEdges(dispstat, nextVblank, nextHblank, nextVcountMatch);
        vblank = nextVblank;
        hblank = nextHblank;
        vcountMatch = nextVcountMatch;

        memory.write16(DISPSTAT, dispstat);
        memory.write16(VCOUNT, scanline);
        return events;
    }

    private Events eventsOnRisingEdges(
            int dispstat,
            boolean nextVblank,
            boolean nextHblank,
            boolean nextVcountMatch) {
        boolean vblankStarted = !vblank && nextVblank;
        boolean hblankStarted = !hblank && nextHblank;
        boolean vcountMatched = !vcountMatch && nextVcountMatch;
        if (interrupts == null) {
            return new Events(vblankStarted ? 1 : 0, hblankStarted ? 1 : 0, vcountMatched ? 1 : 0);
        }
        if (vblankStarted && (dispstat & (1 << 3)) != 0) {
            interrupts.request(GbaInterrupt.VBLANK);
        }
        if (hblankStarted && (dispstat & (1 << 4)) != 0) {
            interrupts.request(GbaInterrupt.HBLANK);
        }
        if (vcountMatched && (dispstat & (1 << 5)) != 0) {
            interrupts.request(GbaInterrupt.VCOUNT);
        }
        return new Events(vblankStarted ? 1 : 0, hblankStarted ? 1 : 0, vcountMatched ? 1 : 0);
    }
}
