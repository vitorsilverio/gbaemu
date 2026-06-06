package dev.vitorsilverio.gbaemu.dma;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Controlador de DMA do GBA.
///
/// Possui seus proprios registradores (0x040000B0-0x040000DF) e usa o bus
/// completo apenas para executar as transferencias de dados entre regioes.
public final class GbaDmaController implements MemorySpace {
    private static final int DMA_BASE       = 0x040000B0;
    private static final int DMA_END        = 0x040000DF;
    private static final int DMA_STRIDE     = 12;
    private static final int ENABLE         = 1 << 15;
    private static final int START_TIMING_MASK = 0b11 << 12;
    private static final int START_IMMEDIATE = 0;
    private static final int START_VBLANK    = 1;
    private static final int START_HBLANK    = 2;
    private static final int START_SPECIAL   = 3;
    private static final int WORD_TRANSFER   = 1 << 10;
    private static final int IRQ_ON_END      = 1 << 14;
    private static final int REPEAT          = 1 << 9;
    private static final int SOURCE_CONTROL_SHIFT = 7;
    private static final int DEST_CONTROL_SHIFT   = 5;

    private final byte[] registers = new byte[4 * DMA_STRIDE];
    private final AddressSpace bus;
    private final GbaInterruptController interrupts;

    public GbaDmaController(AddressSpace bus, GbaInterruptController interrupts) {
        this.bus = bus;
        this.interrupts = interrupts;
    }

    @Override
    public boolean contains(int address) {
        return address >= DMA_BASE && address <= DMA_END;
    }

    @Override
    public int readByte(int address) {
        return registers[address - DMA_BASE] & 0xFF;
    }

    @Override
    public int readHalfWord(int address) {
        int offset = (address & ~1) - DMA_BASE;
        return (registers[offset] & 0xFF) | ((registers[offset + 1] & 0xFF) << 8);
    }

    @Override
    public int readWord(int address) {
        int aligned = address & ~3;
        return readHalfWord(aligned) | (readHalfWord(aligned + 2) << 16);
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
        int offset = (address & ~1) - DMA_BASE;
        registers[offset]     = (byte) value;
        registers[offset + 1] = (byte) (value >>> 8);
    }

    @Override
    public void writeWord(int address, int value) {
        int aligned = address & ~3;
        writeHalfWord(aligned, value);
        writeHalfWord(aligned + 2, value >>> 16);
    }

    public void triggerImmediateTransfers() {
        triggerTransfers(START_IMMEDIATE);
    }

    public void triggerVblankTransfers() {
        triggerTransfers(START_VBLANK);
    }

    public void triggerHblankTransfers() {
        triggerTransfers(START_HBLANK);
    }

    public void triggerAudioFifoTransfers(int fifoRequestMask) {
        if (fifoRequestMask == 0) return;
        for (int channel = 1; channel <= 2; channel++) {
            int control = regRead16(channel, 10);
            if ((control & ENABLE) == 0 || startTiming(control) != START_SPECIAL) continue;
            int destination = regRead32(channel, 4) & destinationMask(channel);
            if ((destination & ~3) == GbaAudio.FIFO_A && (fifoRequestMask & GbaAudio.FIFO_A_REQUEST) != 0) {
                runAudioFifo(channel);
            } else if ((destination & ~3) == GbaAudio.FIFO_B && (fifoRequestMask & GbaAudio.FIFO_B_REQUEST) != 0) {
                runAudioFifo(channel);
            }
        }
    }

    public void run(int channel) {
        checkChannel(channel);
        int control = regRead16(channel, 10);
        if ((control & ENABLE) == 0) return;

        boolean wordTransfer = (control & WORD_TRANSFER) != 0;
        int unitSize = wordTransfer ? 4 : 2;
        int count = regRead16(channel, 8);
        if (count == 0) count = channel == 3 ? 0x10000 : 0x4000;
        int source      = regRead32(channel, 0) & sourceMask(channel);
        int destination = regRead32(channel, 4) & destinationMask(channel);
        int sourceStep      = addressStep((control >>> SOURCE_CONTROL_SHIFT) & 0x3, unitSize);
        int destinationMode = (control >>> DEST_CONTROL_SHIFT) & 0x3;
        int destinationStep = addressStep(destinationMode, unitSize);

        int currentSource = source;
        int currentDest   = destination;
        for (int i = 0; i < count; i++) {
            if (wordTransfer) bus.write32(currentDest, bus.read32(currentSource));
            else              bus.write16(currentDest, bus.read16(currentSource));
            currentSource += sourceStep;
            currentDest   += destinationStep;
        }

        regWrite32(channel, 0, currentSource);
        regWrite32(channel, 4, destinationMode == 3 ? destination : currentDest);

        if ((control & REPEAT) == 0 || startTiming(control) == START_IMMEDIATE) {
            regWrite16(channel, 10, control & ~ENABLE);
        }
        if (interrupts != null && (control & IRQ_ON_END) != 0) {
            interrupts.request(GbaInterrupt.values()[GbaInterrupt.DMA0.ordinal() + channel]);
        }
    }

    private void triggerTransfers(int startTiming) {
        for (int channel = 0; channel < 4; channel++) {
            int control = regRead16(channel, 10);
            if ((control & ENABLE) != 0 && startTiming(control) == startTiming) {
                run(channel);
            }
        }
    }

    private void runAudioFifo(int channel) {
        int control     = regRead16(channel, 10);
        int source      = regRead32(channel, 0) & sourceMask(channel);
        int destination = regRead32(channel, 4) & destinationMask(channel);
        int sourceStep  = addressStep((control >>> SOURCE_CONTROL_SHIFT) & 0x3, 4);

        int currentSource = source;
        for (int i = 0; i < 4; i++) {
            bus.write32(destination, bus.read32(currentSource));
            currentSource += sourceStep;
        }
        regWrite32(channel, 0, currentSource);

        if (interrupts != null && (control & IRQ_ON_END) != 0) {
            interrupts.request(GbaInterrupt.values()[GbaInterrupt.DMA0.ordinal() + channel]);
        }
    }

    // Register helpers using local byte[] registers
    private int regRead16(int channel, int byteOffset) {
        int base = channel * DMA_STRIDE + byteOffset;
        return (registers[base] & 0xFF) | ((registers[base + 1] & 0xFF) << 8);
    }

    private int regRead32(int channel, int byteOffset) {
        return regRead16(channel, byteOffset) | (regRead16(channel, byteOffset + 2) << 16);
    }

    private void regWrite16(int channel, int byteOffset, int value) {
        int base = channel * DMA_STRIDE + byteOffset;
        registers[base]     = (byte) value;
        registers[base + 1] = (byte) (value >>> 8);
    }

    private void regWrite32(int channel, int byteOffset, int value) {
        regWrite16(channel, byteOffset,     value);
        regWrite16(channel, byteOffset + 2, value >>> 16);
    }

    private static int startTiming(int control) {
        return (control & START_TIMING_MASK) >>> 12;
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

    private static void checkChannel(int channel) {
        if (channel < 0 || channel > 3) throw new IllegalArgumentException("DMA channel out of range: " + channel);
    }
}
