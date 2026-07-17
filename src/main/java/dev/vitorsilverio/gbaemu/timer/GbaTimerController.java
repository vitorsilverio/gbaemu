package dev.vitorsilverio.gbaemu.timer;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

import java.util.Arrays;

/// Controlador dos quatro timers do GBA.
public final class GbaTimerController implements MemorySpace {
    private static final int TIMER_BASE   = 0x04000100;
    private static final int TIMER_END    = 0x0400010F;
    private static final int TIMER_STRIDE = 4;
    private static final int ENABLE         = 1 << 7;
    private static final int IRQ_ON_OVERFLOW = 1 << 6;
    private static final int CASCADE        = 1 << 2;
    private static final int PRESCALER_MASK = 0x3;
    private static final int[] PRESCALERS = {1, 64, 256, 1024};

    // Each timer: bytes 0-1 = reload/counter, bytes 2-3 = control
    private final byte[] registers = new byte[4 * TIMER_STRIDE];
    private final GbaInterruptController interrupts;

    private final int[] counters          = new int[4];
    private final int[] reloads           = new int[4];
    private final int[] cycleAccumulators = new int[4];
    private final int[] overflowCounts    = new int[4];
    private final boolean[] enabled       = new boolean[4];

    public GbaTimerController(GbaInterruptController interrupts) {
        this.interrupts = interrupts;
    }

    @Override
    public boolean contains(int address) {
        return address >= TIMER_BASE && address <= TIMER_END;
    }

    @Override
    public int readByte(int address) {
        return registers[address - TIMER_BASE] & 0xFF;
    }

    @Override
    public int readHalfWord(int address) {
        int offset = (address & ~1) - TIMER_BASE;
        return (registers[offset] & 0xFF) | ((registers[offset + 1] & 0xFF) << 8);
    }

    @Override
    public void writeByte(int address, int value) {
        int aligned = address & ~1;
        int shift = (address & 1) * 8;
        int current = readHalfWord(aligned);
        int mask = 0xFF << shift;
        writeHalfWord(aligned, (current & ~mask) | ((value & 0xFF) << shift));
    }

    @Override
    public void writeHalfWord(int address, int value) {
        int offset = (address & ~1) - TIMER_BASE;
        registers[offset]     = (byte) value;
        registers[offset + 1] = (byte) (value >>> 8);
        // Sync state when control register is written
        int timer = offset / TIMER_STRIDE;
        if ((offset % TIMER_STRIDE) == 2) {
            syncTimer(timer, value & 0xFFFF);
        }
    }

    public int tick(int cycles) {
        if (cycles < 0) throw new IllegalArgumentException("cycles must be >= 0");
        Arrays.fill(overflowCounts, 0);
        int overflowMask = 0;
        for (int timer = 0; timer < 4; timer++) {
            int control = control(timer);
            if (!enabled[timer] || (control & CASCADE) != 0) continue;
            int prescaler = PRESCALERS[control & PRESCALER_MASK];
            cycleAccumulators[timer] += cycles;
            int increments = cycleAccumulators[timer] / prescaler;
            if (increments > 0) {
                cycleAccumulators[timer] -= increments * prescaler;
                overflowMask |= advanceCounter(timer, increments);
            }
        }
        return overflowMask;
    }

    public int overflowCount(int timer) {
        checkTimer(timer);
        return overflowCounts[timer];
    }

    /// Diagnostic (task D4): the CPU-cycle period between overflows of a running timer
    /// (reload-to-0x10000 distance times its prescaler), or 0 if the timer is stopped.
    public int overflowPeriodCycles(int timer) {
        checkTimer(timer);
        if (!enabled[timer]) {
            return 0;
        }
        int prescaler = PRESCALERS[control(timer) & PRESCALER_MASK];
        return (0x10000 - reloads[timer]) * prescaler;
    }

    /// Serializes all timer state (registers + counters/reloads/accumulators) into a save state.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.write(registers);
        for (int i = 0; i < 4; i++) {
            out.writeInt(counters[i]);
            out.writeInt(reloads[i]);
            out.writeInt(cycleAccumulators[i]);
            out.writeInt(overflowCounts[i]);
            out.writeBoolean(enabled[i]);
        }
    }

    /// Restores all timer state from a save state.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(registers);
        for (int i = 0; i < 4; i++) {
            counters[i] = in.readInt();
            reloads[i] = in.readInt();
            cycleAccumulators[i] = in.readInt();
            overflowCounts[i] = in.readInt();
            enabled[i] = in.readBoolean();
        }
    }

    private void syncTimer(int timer, int newControl) {
        boolean nextEnabled = (newControl & ENABLE) != 0;
        if (!enabled[timer] && nextEnabled) {
            int base = timer * TIMER_STRIDE;
            reloads[timer] = (registers[base] & 0xFF) | ((registers[base + 1] & 0xFF) << 8);
            counters[timer] = reloads[timer];
            writeCounterToRegisters(timer, counters[timer]);
            cycleAccumulators[timer] = 0;
        }
        if (enabled[timer] && !nextEnabled) {
            cycleAccumulators[timer] = 0;
        }
        enabled[timer] = nextEnabled;
        // Update reload from current counter register when not running
        if (!nextEnabled) {
            int base = timer * TIMER_STRIDE;
            reloads[timer] = (registers[base] & 0xFF) | ((registers[base + 1] & 0xFF) << 8);
        }
    }

    private int advanceCounter(int timer, int increments) {
        if (increments <= 0) return 0;
        int distanceToOverflow = 0x10000 - counters[timer];
        if (increments < distanceToOverflow) {
            counters[timer] = (counters[timer] + increments) & 0xFFFF;
            writeCounterToRegisters(timer, counters[timer]);
            return 0;
        }
        int remaining = increments - distanceToOverflow;
        int reloadPeriod = 0x10000 - reloads[timer];
        int extraOverflows = reloadPeriod <= 0 ? 0 : remaining / reloadPeriod;
        int remainder = reloadPeriod <= 0 ? 0 : remaining % reloadPeriod;
        int overflowCount = 1 + extraOverflows;
        counters[timer] = (reloads[timer] + remainder) & 0xFFFF;
        writeCounterToRegisters(timer, counters[timer]);
        return overflow(timer, overflowCount);
    }

    private int overflow(int timer, int overflowCount) {
        int overflowMask = 1 << timer;
        overflowCounts[timer] += overflowCount;
        if ((control(timer) & IRQ_ON_OVERFLOW) != 0 && interrupts != null) {
            interrupts.request(GbaInterrupt.values()[GbaInterrupt.TIMER0.ordinal() + timer]);
        }
        int nextTimer = timer + 1;
        if (nextTimer < 4 && enabled[nextTimer] && (control(nextTimer) & CASCADE) != 0) {
            overflowMask |= advanceCounter(nextTimer, overflowCount);
        }
        return overflowMask;
    }

    private void writeCounterToRegisters(int timer, int value) {
        int base = timer * TIMER_STRIDE;
        registers[base]     = (byte) value;
        registers[base + 1] = (byte) (value >>> 8);
    }

    private int control(int timer) {
        int base = timer * TIMER_STRIDE + 2;
        return (registers[base] & 0xFF) | ((registers[base + 1] & 0xFF) << 8);
    }

    private static void checkTimer(int timer) {
        if (timer < 0 || timer > 3) throw new IllegalArgumentException("Timer index out of range: " + timer);
    }
}
