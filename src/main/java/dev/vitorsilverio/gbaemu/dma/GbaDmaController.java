package dev.vitorsilverio.gbaemu.dma;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Controlador inicial de DMA do GBA.
///
/// Implementa disparo imediato para DMA0-3. Os modos HBlank/VBlank/Special
/// ficam preservados no registrador, mas ainda nao sao agendados pelo timing.
public final class GbaDmaController {
    private static final int DMA_BASE = 0x040000B0;
    private static final int DMA_STRIDE = 12;
    private static final int ENABLE = 1 << 15;
    private static final int START_TIMING_MASK = 0b11 << 12;
    private static final int START_IMMEDIATE = 0;
    private static final int WORD_TRANSFER = 1 << 10;
    private static final int IRQ_ON_END = 1 << 14;
    private static final int REPEAT = 1 << 9;
    private static final int SOURCE_CONTROL_SHIFT = 7;
    private static final int DEST_CONTROL_SHIFT = 5;

    private final AddressSpace memory;
    private final GbaInterruptController interrupts;

    public GbaDmaController(AddressSpace memory) {
        this(memory, null);
    }

    public GbaDmaController(AddressSpace memory, GbaInterruptController interrupts) {
        this.memory = memory;
        this.interrupts = interrupts;
    }

    public void triggerImmediateTransfers() {
        for (int channel = 0; channel < 4; channel++) {
            if (isImmediateEnabled(channel)) {
                run(channel);
            }
        }
    }

    public void run(int channel) {
        checkChannel(channel);

        int base = channelBase(channel);
        int control = memory.read16(base + 10);
        if ((control & ENABLE) == 0) {
            return;
        }

        boolean wordTransfer = (control & WORD_TRANSFER) != 0;
        int unitSize = wordTransfer ? 4 : 2;
        int count = memory.read16(base + 8);
        if (count == 0) {
            count = channel == 3 ? 0x10000 : 0x4000;
        }
        int source = memory.read32(base) & sourceMask(channel);
        int destination = memory.read32(base + 4) & destinationMask(channel);
        int sourceStep = addressStep((control >>> SOURCE_CONTROL_SHIFT) & 0x3, unitSize);
        int destinationMode = (control >>> DEST_CONTROL_SHIFT) & 0x3;
        int destinationStep = addressStep(destinationMode, unitSize);

        int currentSource = source;
        int currentDestination = destination;
        for (int i = 0; i < count; i++) {
            if (wordTransfer) {
                memory.write32(currentDestination, memory.read32(currentSource));
            } else {
                memory.write16(currentDestination, memory.read16(currentSource));
            }
            currentSource += sourceStep;
            currentDestination += destinationStep;
        }

        memory.write32(base, currentSource);
        if (destinationMode == 3) {
            memory.write32(base + 4, destination);
        } else {
            memory.write32(base + 4, currentDestination);
        }

        if ((control & REPEAT) == 0 || immediateStart(control)) {
            memory.write16(base + 10, control & ~ENABLE);
        }
        if (interrupts != null && (control & IRQ_ON_END) != 0) {
            interrupts.request(GbaInterrupt.values()[GbaInterrupt.DMA0.ordinal() + channel]);
        }
    }

    private boolean isImmediateEnabled(int channel) {
        int control = memory.read16(channelBase(channel) + 10);
        return (control & ENABLE) != 0 && immediateStart(control);
    }

    private static boolean immediateStart(int control) {
        return (control & START_TIMING_MASK) == START_IMMEDIATE;
    }

    private static int addressStep(int mode, int unitSize) {
        return switch (mode) {
            case 0, 3 -> unitSize;
            case 1 -> -unitSize;
            case 2 -> 0;
            default -> throw new IllegalArgumentException("Invalid DMA address mode: " + mode);
        };
    }

    private static int sourceMask(int channel) {
        return channel == 0 ? 0x07FF_FFFF : 0x0FFF_FFFF;
    }

    private static int destinationMask(int channel) {
        return channel == 3 ? 0x0FFF_FFFF : 0x07FF_FFFF;
    }

    private static int channelBase(int channel) {
        checkChannel(channel);
        return DMA_BASE + channel * DMA_STRIDE;
    }

    private static void checkChannel(int channel) {
        if (channel < 0 || channel > 3) {
            throw new IllegalArgumentException("DMA channel must be between 0 and 3: " + channel);
        }
    }
}
