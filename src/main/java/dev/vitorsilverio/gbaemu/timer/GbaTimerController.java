package dev.vitorsilverio.gbaemu.timer;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

import java.util.Arrays;

/// Controlador inicial dos quatro timers do GBA.
public final class GbaTimerController {
    private static final int TIMER_BASE = 0x04000100;
    private static final int TIMER_STRIDE = 4;
    private static final int ENABLE = 1 << 7;
    private static final int IRQ_ON_OVERFLOW = 1 << 6;
    private static final int CASCADE = 1 << 2;
    private static final int PRESCALER_MASK = 0x3;
    private static final int[] PRESCALERS = {1, 64, 256, 1024};

    private final AddressSpace memory;
    private final GbaInterruptController interrupts;
    private final int[] reloads = new int[4];
    private final int[] counters = new int[4];
    private final int[] cycleAccumulators = new int[4];
    private final int[] overflowCounts = new int[4];
    private final boolean[] enabled = new boolean[4];

    public GbaTimerController(AddressSpace memory) {
        this(memory, null);
    }

    public GbaTimerController(AddressSpace memory, GbaInterruptController interrupts) {
        this.memory = memory;
        this.interrupts = interrupts;
        syncFromRegisters();
    }

    public int tick(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be >= 0");
        }
        syncFromRegisters();
        Arrays.fill(overflowCounts, 0);
        int overflowMask = 0;
        for (int timer = 0; timer < 4; timer++) {
            int control = control(timer);
            if (!enabled[timer] || (control & CASCADE) != 0) {
                continue;
            }
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

    public int counter(int timer) {
        checkTimer(timer);
        syncFromRegisters();
        return counters[timer];
    }

    public int reload(int timer) {
        checkTimer(timer);
        syncFromRegisters();
        return reloads[timer];
    }

    public int overflowCount(int timer) {
        checkTimer(timer);
        return overflowCounts[timer];
    }

    private int increment(int timer) {
        return advanceCounter(timer, 1);
    }

    private int advanceCounter(int timer, int increments) {
        if (increments <= 0) {
            return 0;
        }
        int distanceToOverflow = 0x10000 - counters[timer];
        if (increments < distanceToOverflow) {
            counters[timer] = (counters[timer] + increments) & 0xFFFF;
            writeCounter(timer, counters[timer]);
            return 0;
        }

        int remaining = increments - distanceToOverflow;
        int reloadPeriod = 0x10000 - reloads[timer];
        int extraOverflows = reloadPeriod <= 0 ? 0 : remaining / reloadPeriod;
        int remainder = reloadPeriod <= 0 ? 0 : remaining % reloadPeriod;
        int overflowCount = 1 + extraOverflows;
        counters[timer] = (reloads[timer] + remainder) & 0xFFFF;
        writeCounter(timer, counters[timer]);
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

    private void syncFromRegisters() {
        for (int timer = 0; timer < 4; timer++) {
            int currentControl = control(timer);
            boolean nextEnabled = (currentControl & ENABLE) != 0;
            int reload = memory.read16(timerBase(timer));
            if (!enabled[timer]) {
                reloads[timer] = reload;
            } else if (reload != counters[timer]) {
                reloads[timer] = reload;
            }
            if (!enabled[timer] && nextEnabled) {
                counters[timer] = reloads[timer];
                writeCounter(timer, counters[timer]);
                cycleAccumulators[timer] = 0;
            }
            if (enabled[timer] && !nextEnabled) {
                cycleAccumulators[timer] = 0;
            }
            enabled[timer] = nextEnabled;
        }
    }

    private int control(int timer) {
        return memory.read16(timerBase(timer) + 2);
    }

    private void writeCounter(int timer, int value) {
        int address = timerBase(timer);
        memory.write8(address, value);
        memory.write8(address + 1, value >>> 8);
    }

    private static int timerBase(int timer) {
        checkTimer(timer);
        return TIMER_BASE + timer * TIMER_STRIDE;
    }

    private static void checkTimer(int timer) {
        if (timer < 0 || timer > 3) {
            throw new IllegalArgumentException("Timer index must be between 0 and 3: " + timer);
        }
    }

    @Override
    public String toString() {
        return "GbaTimerController{"
                + "reloads=" + Arrays.toString(reloads)
                + ", counters=" + Arrays.toString(counters)
                + '}';
    }
}
