package dev.vitorsilverio.gbaemu.bios;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.system.GbaSystemControl;

import java.util.ArrayList;
import java.util.List;

/// Implementacao HLE de chamadas BIOS usadas pelo caminho skip BIOS.
public final class GbaBiosSwi {
    private static final int SWI_SOFT_RESET = 0x00;
    private static final int SWI_REGISTER_RAM_RESET = 0x01;
    private static final int SWI_HALT = 0x02;
    private static final int SWI_STOP = 0x03;
    private static final int SWI_INTR_WAIT = 0x04;
    private static final int SWI_VBLANK_INTR_WAIT = 0x05;
    private static final int SWI_DIV = 0x06;
    private static final int SWI_DIV_ARM = 0x07;
    private static final int SWI_SQRT = 0x08;
    private static final int SWI_ARCTAN = 0x09;
    private static final int SWI_ARCTAN2 = 0x0A;
    private static final int SWI_CPU_SET = 0x0B;
    private static final int SWI_CPU_FAST_SET = 0x0C;
    private static final int SWI_GET_BIOS_CHECKSUM = 0x0D;
    private static final int SWI_BG_AFFINE_SET = 0x0E;
    private static final int SWI_OBJ_AFFINE_SET = 0x0F;
    private static final int SWI_BIT_UNPACK = 0x10;
    private static final int SWI_LZ77_UNCOMP_WRAM = 0x11;
    private static final int SWI_LZ77_UNCOMP_VRAM = 0x12;
    private static final int SWI_HUFF_UNCOMP = 0x13;
    private static final int SWI_RL_UNCOMP_WRAM = 0x14;
    private static final int SWI_RL_UNCOMP_VRAM = 0x15;
    private static final int SWI_DIFF_8_BIT_UN_FILTER_WRAM = 0x16;
    private static final int SWI_DIFF_8_BIT_UN_FILTER_VRAM = 0x17;
    private static final int SWI_DIFF_16_BIT_UN_FILTER = 0x18;
    private static final int SWI_SOUND_BIAS = 0x19;
    private static final int SWI_SOUND_DRIVER_INIT = 0x1A;
    private static final int SWI_SOUND_DRIVER_MODE = 0x1B;
    private static final int SWI_SOUND_DRIVER_MAIN = 0x1C;
    private static final int SWI_SOUND_DRIVER_VSYNC = 0x1D;
    private static final int SWI_SOUND_CHANNEL_CLEAR = 0x1E;
    private static final int SWI_MIDI_KEY_2_FRAME = 0x1F;
    private static final int SWI_SOUND_WHATEVER_0 = 0x20;
    private static final int SWI_SOUND_WHATEVER_1 = 0x21;
    private static final int SWI_SOUND_WHATEVER_2 = 0x22;
    private static final int SWI_SOUND_WHATEVER_3 = 0x23;
    private static final int SWI_SOUND_WHATEVER_4 = 0x24;
    private static final int SWI_MULTI_BOOT = 0x25;
    private static final int SWI_HARD_RESET = 0x26;
    private static final int SWI_CUSTOM_HALT = 0x27;
    private static final int SWI_SOUND_DRIVER_VSYNC_OFF = 0x28;
    private static final int SWI_SOUND_DRIVER_VSYNC_ON = 0x29;
    private static final int SWI_SOUND_GET_JUMP_LIST = 0x2A;

    private static final int ROM_ENTRY_POINT = 0x08000000;
    private static final int MULTIBOOT_ENTRY_POINT = 0x02000000;
    private static final int SOFT_RESET_FLAG = 0x03007FFA;
    private static final int BIOS_CHECKSUM = 0xBAAE187F;
    private static final int DEBUG_EVENT_LIMIT = 96;
    private static final int[] CALL_COUNTS = new int[SWI_SOUND_GET_JUMP_LIST + 1];
    private static final List<String> DEBUG_EVENTS = new ArrayList<>();


    private GbaBiosSwi() {
    }

    public static SwiDispatcher dispatcher(AddressSpace memory, GbaSystemControl systemControl) {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        register(dispatcher, SWI_SOFT_RESET, state -> softReset(memory, state));
        register(dispatcher, SWI_REGISTER_RAM_RESET, state -> registerRamReset(memory, state));
        register(dispatcher, SWI_HALT, state -> halt(systemControl, state));
        register(dispatcher, SWI_STOP, state -> stop(systemControl, state));
        register(dispatcher, SWI_INTR_WAIT, state -> waitForInterrupt(systemControl, state));
        register(dispatcher, SWI_VBLANK_INTR_WAIT, state -> waitForInterrupt(systemControl, state));
        register(dispatcher, SWI_DIV, GbaBiosSwi::div);
        register(dispatcher, SWI_DIV_ARM, GbaBiosSwi::divArm);
        register(dispatcher, SWI_SQRT, GbaBiosSwi::sqrt);
        register(dispatcher, SWI_ARCTAN, GbaBiosSwi::arcTan);
        register(dispatcher, SWI_ARCTAN2, GbaBiosSwi::arcTan2);
        register(dispatcher, SWI_CPU_SET, state -> cpuSet(memory, state));
        register(dispatcher, SWI_CPU_FAST_SET, state -> cpuFastSet(memory, state));
        register(dispatcher, SWI_GET_BIOS_CHECKSUM, GbaBiosSwi::getBiosChecksum);
        register(dispatcher, SWI_BG_AFFINE_SET, state -> bgAffineSet(memory, state));
        register(dispatcher, SWI_OBJ_AFFINE_SET, state -> objAffineSet(memory, state));
        register(dispatcher, SWI_BIT_UNPACK, state -> bitUnPack(memory, state));
        register(dispatcher, SWI_LZ77_UNCOMP_WRAM, state -> lz77UnComp(memory, state, false));
        register(dispatcher, SWI_LZ77_UNCOMP_VRAM, state -> lz77UnComp(memory, state, true));
        register(dispatcher, SWI_HUFF_UNCOMP, state -> huffUnComp(memory, state));
        register(dispatcher, SWI_RL_UNCOMP_WRAM, state -> rlUnComp(memory, state, false));
        register(dispatcher, SWI_RL_UNCOMP_VRAM, state -> rlUnComp(memory, state, true));
        register(dispatcher, SWI_DIFF_8_BIT_UN_FILTER_WRAM, state -> diff8BitUnFilter(memory, state, false));
        register(dispatcher, SWI_DIFF_8_BIT_UN_FILTER_VRAM, state -> diff8BitUnFilter(memory, state, true));
        register(dispatcher, SWI_DIFF_16_BIT_UN_FILTER, state -> diff16BitUnFilter(memory, state));
        register(dispatcher, SWI_SOUND_BIAS, state -> soundBias(memory, state));
        register(dispatcher, SWI_SOUND_DRIVER_INIT, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_DRIVER_MODE, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_DRIVER_MAIN, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_DRIVER_VSYNC, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_CHANNEL_CLEAR, state -> soundChannelClear(memory, state));
        register(dispatcher, SWI_MIDI_KEY_2_FRAME, GbaBiosSwi::midiKeyToFrequency);
        register(dispatcher, SWI_SOUND_WHATEVER_0, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_WHATEVER_1, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_WHATEVER_2, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_WHATEVER_3, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_WHATEVER_4, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_MULTI_BOOT, state -> state.withR0(1));
        register(dispatcher, SWI_HARD_RESET, state -> hardReset(memory, state));
        register(dispatcher, SWI_CUSTOM_HALT, state -> halt(systemControl, state));
        register(dispatcher, SWI_SOUND_DRIVER_VSYNC_OFF, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_DRIVER_VSYNC_ON, GbaBiosSwi::returnUnchanged);
        register(dispatcher, SWI_SOUND_GET_JUMP_LIST, state -> state.withR0(0));
        dispatcher.fallbackWithNumber((swi, state) -> {
            int thumbSwi = swi & 0xFF;
            if ((swi & 0xFFFF00) == 0xFFFF00) {
                return thumbSwi <= SWI_SOUND_GET_JUMP_LIST
                        ? dispatcher.dispatch(thumbSwi, state)
                        : state;
            }
            throw new UnsupportedOperationException("GBA BIOS SWI not implemented: 0x"
                    + Integer.toHexString(swi));
        });
        return dispatcher;
    }

    public static int[] callCountsSnapshot() {
        return CALL_COUNTS.clone();
    }

    public static List<String> debugEventsSnapshot() {
        return List.copyOf(DEBUG_EVENTS);
    }

    private static void register(SwiDispatcher dispatcher, int swi, java.util.function.Function<CpuState, CpuState> handler) {
        dispatcher.register(swi, state -> {
            CALL_COUNTS[swi]++;
            return handler.apply(state);
        });
    }

    private static CpuState softReset(AddressSpace memory, CpuState state) {
        int target = memory.read8(SOFT_RESET_FLAG) == 0 ? ROM_ENTRY_POINT : MULTIBOOT_ENTRY_POINT;
        return new CpuState(0, 0, 0, 0, 0x03007F00, 0, target, state.cpsr() & ~0x20);
    }

    private static CpuState hardReset(AddressSpace memory, CpuState state) {
        memory.write8(SOFT_RESET_FLAG, 0);
        return softReset(memory, state);
    }

    private static CpuState registerRamReset(AddressSpace memory, CpuState state) {
        int flags = state.r0();
        if ((flags & 0x01) != 0) fill(memory, 0x02000000, 256 * 1024, 0);
        if ((flags & 0x02) != 0) fill(memory, 0x03000000,  32 * 1024, 0);
        if ((flags & 0x04) != 0) fill(memory, 0x05000000,       1024, 0);
        if ((flags & 0x08) != 0) fill(memory, 0x06000000,  96 * 1024, 0);
        if ((flags & 0x10) != 0) fill(memory, 0x07000000,       1024, 0);
        return state;
    }

    private static CpuState halt(GbaSystemControl systemControl, CpuState state) {
        systemControl.writeHaltControl(0);
        return state;
    }

    private static CpuState stop(GbaSystemControl systemControl, CpuState state) {
        systemControl.writeHaltControl(0x80);
        return state;
    }

    private static CpuState waitForInterrupt(GbaSystemControl systemControl, CpuState state) {
        systemControl.writeHaltControl(0);
        return state.withR0(0);
    }

    private static CpuState returnUnchanged(CpuState state) {
        return state;
    }

    private static CpuState div(CpuState state) {
        return divResult(state, state.r0(), state.r1());
    }

    private static CpuState divArm(CpuState state) {
        return divResult(state, state.r1(), state.r0());
    }

    private static CpuState divResult(CpuState state, int numerator, int denominator) {
        int quotient = denominator == 0 ? 0 : numerator / denominator;
        int remainder = denominator == 0 ? numerator : numerator % denominator;
        return new CpuState(
                quotient,
                remainder,
                state.r2(),
                Math.abs(quotient),
                state.sp(),
                state.lr(),
                state.pc(),
                state.cpsr());
    }

    private static CpuState sqrt(CpuState state) {
        int value = state.r0();
        int result = (int) Math.sqrt(value & 0xFFFF_FFFFL);
        return state.withR0(result);
    }

    private static CpuState arcTan(CpuState state) {
        // HLE approximation: replace with a BIOS-accurate fixed-point routine if bit-exact results become necessary.
        double tangent = signed16(state.r0()) / 16384.0;
        return state.withR0(angleToBiosUnits(Math.atan(tangent)));
    }

    private static CpuState arcTan2(CpuState state) {
        // HLE approximation: Java atan2 follows the expected quadrants, but not the original BIOS algorithm bit-for-bit.
        double x = signed16(state.r0());
        double y = signed16(state.r1());
        return state.withR0(angleToBiosUnits(Math.atan2(y, x)));
    }

    private static CpuState getBiosChecksum(CpuState state) {
        return state.withR0(BIOS_CHECKSUM);
    }

    private static CpuState cpuSet(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int mode = state.r2();
        int count = mode & 0x1F_FFFF;
        boolean fixedSource = (mode & (1 << 24)) != 0;
        boolean wordTransfer = (mode & (1 << 26)) != 0;
        int unitSize = wordTransfer ? 4 : 2;
        recordMemoryEvent(
                "CpuSet src=0x%08X dst=0x%08X mode=0x%08X bytes=0x%X",
                source,
                destination,
                mode,
                count * unitSize);

        for (int i = 0; i < count; i++) {
            if (wordTransfer) {
                memory.write32(destination, memory.read32(source));
            } else {
                memory.write16(destination, memory.read16(source));
            }
            if (!fixedSource) {
                source += unitSize;
            }
            destination += unitSize;
        }
        return state;
    }

    private static CpuState cpuFastSet(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int mode = state.r2();
        int count = mode & 0x1F_FFFF;
        boolean fixedSource = (mode & (1 << 24)) != 0;
        int words = (count + 7) & ~7;
        recordMemoryEvent(
                "CpuFastSet src=0x%08X dst=0x%08X mode=0x%08X bytes=0x%X heapHead=0x%08X heap0Size=0x%08X heap0Next=0x%08X",
                source,
                destination,
                mode,
                words * 4,
                memory.read32(0x02020008),
                heapWord(memory, memory.read32(0x02020008), 4),
                heapWord(memory, memory.read32(0x02020008), 12));

        for (int i = 0; i < words; i++) {
            memory.write32(destination, memory.read32(source));
            if (!fixedSource) {
                source += 4;
            }
            destination += 4;
        }
        return state;
    }

    private static CpuState bgAffineSet(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int count = state.r2();

        for (int i = 0; i < count; i++) {
            int sourceBase = source + i * 20;
            int destinationBase = destination + i * 16;
            int textureCenterX = memory.read32(sourceBase);
            int textureCenterY = memory.read32(sourceBase + 4);
            int screenCenterX = signed16(memory.read16(sourceBase + 8));
            int screenCenterY = signed16(memory.read16(sourceBase + 10));
            int scaleX = signed16(memory.read16(sourceBase + 12));
            int scaleY = signed16(memory.read16(sourceBase + 14));
            int angle = memory.read16(sourceBase + 16) & 0xFFFF;

            // HLE approximation: BIOS uses its own sine table/fixed-point rounding.
            int cos = cos8(angle);
            int sin = sin8(angle);
            int pa = (cos * scaleX) >> 8;
            int pb = (-sin * scaleX) >> 8;
            int pc = (sin * scaleY) >> 8;
            int pd = (cos * scaleY) >> 8;
            int startX = textureCenterX - screenCenterX * pa - screenCenterY * pb;
            int startY = textureCenterY - screenCenterX * pc - screenCenterY * pd;

            memory.write16(destinationBase, pa);
            memory.write16(destinationBase + 2, pb);
            memory.write16(destinationBase + 4, pc);
            memory.write16(destinationBase + 6, pd);
            memory.write32(destinationBase + 8, startX);
            memory.write32(destinationBase + 12, startY);
        }
        return state;
    }

    private static CpuState objAffineSet(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int count = state.r2();
        int offset = state.r3();

        for (int i = 0; i < count; i++) {
            int sourceBase = source + i * 6;
            int destinationBase = destination + i * offset * 8;
            int scaleX = signed16(memory.read16(sourceBase));
            int scaleY = signed16(memory.read16(sourceBase + 2));
            int angle = memory.read16(sourceBase + 4) & 0xFFFF;

            // HLE approximation: BIOS uses its own sine table/fixed-point rounding.
            int cos = cos8(angle);
            int sin = sin8(angle);
            int pa = (cos * scaleX) >> 8;
            int pb = (-sin * scaleX) >> 8;
            int pc = (sin * scaleY) >> 8;
            int pd = (cos * scaleY) >> 8;

            memory.write16(destinationBase, pa);
            memory.write16(destinationBase + offset * 2, pb);
            memory.write16(destinationBase + offset * 4, pc);
            memory.write16(destinationBase + offset * 6, pd);
        }
        return state;
    }

    private static CpuState bitUnPack(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int descriptor = state.r2();
        int length = memory.read16(descriptor);
        int sourceWidth = memory.read8(descriptor + 2);
        int destinationWidth = memory.read8(descriptor + 3);
        int offsetDescriptor = memory.read32(descriptor + 4);
        int offset = offsetDescriptor & 0x7FFF_FFFF;
        boolean offsetZeroData = (offsetDescriptor & 0x8000_0000) != 0;
        int destinationMask = destinationWidth == 32 ? -1 : (1 << destinationWidth) - 1;
        int outputWord = 0;
        int outputBits = 0;
        int outputAddress = destination;

        for (int byteIndex = 0; byteIndex < length; byteIndex++) {
            int sourceByte = memory.read8(source + byteIndex);
            for (int bit = 0; bit < 8; bit += sourceWidth) {
                int value = (sourceByte >>> bit) & ((1 << sourceWidth) - 1);
                if (value != 0 || offsetZeroData) {
                    value += offset;
                }
                outputWord |= (value & destinationMask) << outputBits;
                outputBits += destinationWidth;
                if (outputBits >= 32) {
                    memory.write32(outputAddress, outputWord);
                    outputAddress += 4;
                    outputWord = 0;
                    outputBits = 0;
                }
            }
        }
        if (outputBits > 0) {
            memory.write32(outputAddress, outputWord);
        }
        return state;
    }

    private static CpuState lz77UnComp(AddressSpace memory, CpuState state, boolean vram) {
        int source = state.r0();
        int destination = state.r1();
        int header = memory.read32(source);
        int outputLength = header >>> 8;
        recordMemoryEvent(
                "LZ77%s src=0x%08X dst=0x%08X bytes=0x%X",
                vram ? "Vram" : "Wram",
                source,
                destination,
                outputLength);
        byte[] output = new byte[outputLength];
        int sourceAddress = source + 4;
        int outputIndex = 0;

        while (outputIndex < outputLength) {
            int flags = memory.read8(sourceAddress++);
            for (int bit = 7; bit >= 0 && outputIndex < outputLength; bit--) {
                if ((flags & (1 << bit)) == 0) {
                    output[outputIndex++] = (byte) memory.read8(sourceAddress++);
                    continue;
                }
                int first = memory.read8(sourceAddress++);
                int second = memory.read8(sourceAddress++);
                int length = (first >>> 4) + 3;
                int displacement = ((first & 0xF) << 8) | second;
                int copySource = outputIndex - displacement - 1;
                for (int i = 0; i < length && outputIndex < outputLength; i++) {
                    output[outputIndex] = output[copySource + i];
                    outputIndex++;
                }
            }
        }

        writeDecompressed(memory, destination, output, vram);
        return state;
    }

    private static CpuState rlUnComp(AddressSpace memory, CpuState state, boolean vram) {
        int source = state.r0();
        int destination = state.r1();
        int header = memory.read32(source);
        int outputLength = header >>> 8;
        recordMemoryEvent(
                "RL%s src=0x%08X dst=0x%08X bytes=0x%X",
                vram ? "Vram" : "Wram",
                source,
                destination,
                outputLength);
        byte[] output = new byte[outputLength];
        int sourceAddress = source + 4;
        int outputIndex = 0;

        while (outputIndex < outputLength) {
            int block = memory.read8(sourceAddress++);
            if ((block & 0x80) == 0) {
                int length = (block & 0x7F) + 1;
                for (int i = 0; i < length && outputIndex < outputLength; i++) {
                    output[outputIndex++] = (byte) memory.read8(sourceAddress++);
                }
            } else {
                int length = (block & 0x7F) + 3;
                int value = memory.read8(sourceAddress++);
                for (int i = 0; i < length && outputIndex < outputLength; i++) {
                    output[outputIndex++] = (byte) value;
                }
            }
        }

        writeDecompressed(memory, destination, output, vram);
        return state;
    }

    private static CpuState huffUnComp(AddressSpace memory, CpuState state) {
        int source = state.r0() & ~3;
        int destination = state.r1();
        int header = memory.read32(source);
        int outputLength = header >>> 8;
        int bitsPerSymbol = header & 0xF;
        if (bitsPerSymbol == 0 || bitsPerSymbol == 1 || (32 % bitsPerSymbol) != 0) {
            return state;
        }

        int treeSize = memory.read8(source + 4) * 2 + 1;
        int treeBase = source + 5;
        int bitstreamAddress = treeBase + treeSize;
        int nodePointer = treeBase;
        int node = memory.read8(nodePointer);
        int outputBlock = 0;
        int outputBits = 0;
        int remaining = outputLength;
        int symbolMask = (1 << bitsPerSymbol) - 1;

        while (remaining > 0) {
            int bitstream = memory.read32(bitstreamAddress);
            bitstreamAddress += 4;
            for (int bit = 0; bit < 32 && remaining > 0; bit++, bitstream <<= 1) {
                int next = (nodePointer & ~1) + ((node & 0x3F) * 2) + 2;
                int symbol;
                if ((bitstream & 0x8000_0000) != 0) {
                    if ((node & (1 << 6)) == 0) {
                        nodePointer = next + 1;
                        node = memory.read8(nodePointer);
                        continue;
                    }
                    symbol = memory.read8(next + 1);
                } else {
                    if ((node & (1 << 7)) == 0) {
                        nodePointer = next;
                        node = memory.read8(nodePointer);
                        continue;
                    }
                    symbol = memory.read8(next);
                }

                outputBlock |= (symbol & symbolMask) << outputBits;
                outputBits += bitsPerSymbol;
                nodePointer = treeBase;
                node = memory.read8(nodePointer);
                if (outputBits == 32) {
                    memory.write32(destination, outputBlock);
                    destination += 4;
                    remaining -= 4;
                    outputBlock = 0;
                    outputBits = 0;
                }
            }
        }
        return new CpuState(
                bitstreamAddress,
                destination,
                state.r2(),
                state.r3(),
                state.sp(),
                state.lr(),
                state.pc(),
                state.cpsr());
    }

    private static CpuState diff8BitUnFilter(AddressSpace memory, CpuState state, boolean vram) {
        int source = state.r0();
        int destination = state.r1();
        int header = memory.read32(source);
        int outputLength = header >>> 8;
        byte[] output = new byte[outputLength];
        int sourceAddress = source + 4;
        int previous = 0;

        for (int i = 0; i < outputLength; i++) {
            previous = (previous + memory.read8(sourceAddress++)) & 0xFF;
            output[i] = (byte) previous;
        }

        writeDecompressed(memory, destination, output, vram);
        return state;
    }

    private static CpuState diff16BitUnFilter(AddressSpace memory, CpuState state) {
        int source = state.r0();
        int destination = state.r1();
        int header = memory.read32(source);
        int outputLength = header >>> 8;
        int sourceAddress = source + 4;
        int previous = 0;

        for (int i = 0; i < outputLength; i += 2) {
            previous = (previous + memory.read16(sourceAddress)) & 0xFFFF;
            memory.write16(destination + i, previous);
            sourceAddress += 2;
        }
        return state;
    }

    private static CpuState soundBias(AddressSpace memory, CpuState state) {
        memory.write16(GbaAudio.SOUNDBIAS, state.r0() == 0 ? 0 : 0x0200);
        return state;
    }

    private static CpuState soundChannelClear(AddressSpace memory, CpuState state) {
        for (int address = 0x04000060; address <= GbaAudio.SOUNDCNT_X; address += 2) {
            memory.write16(address, 0);
        }
        memory.write16(GbaAudio.SOUNDBIAS, 0x0200);
        return state;
    }

    private static CpuState midiKeyToFrequency(CpuState state) {
        return state.withR0(0);
    }

    private static void writeDecompressed(AddressSpace memory, int destination, byte[] output, boolean vram) {
        if (!vram) {
            for (int i = 0; i < output.length; i++) {
                memory.write8(destination + i, output[i]);
            }
            return;
        }
        for (int i = 0; i < output.length; i += 2) {
            int low = output[i] & 0xFF;
            int high = i + 1 < output.length ? output[i + 1] & 0xFF : 0;
            memory.write16(destination + i, low | (high << 8));
        }
    }

    private static void fill(AddressSpace memory, int start, int size, int value) {
        for (int offset = 0; offset < size; offset += 4) {
            memory.write32(start + offset, value);
        }
    }

    private static void recordMemoryEvent(String format, Object... args) {
        if (DEBUG_EVENTS.size() >= DEBUG_EVENT_LIMIT) {
            return;
        }
        String event = String.format(format, args);
        if (event.contains("dst=0x02") || event.contains("dst=0x03")) {
            DEBUG_EVENTS.add(event);
        }
    }

    private static int heapWord(AddressSpace memory, int address, int offset) {
        if (address < 0x02000000 || address >= 0x02040000) {
            return 0;
        }
        return memory.read32(address + offset);
    }

    private static int signed16(int value) {
        return (short) value;
    }

    private static int angleToBiosUnits(double radians) {
        int value = (int) Math.round(radians * 32768.0 / Math.PI);
        return value & 0xFFFF;
    }

    private static int sin8(int angle) {
        return (int) Math.round(Math.sin(angle * 2.0 * Math.PI / 65536.0) * 256.0);
    }

    private static int cos8(int angle) {
        return (int) Math.round(Math.cos(angle * 2.0 * Math.PI / 65536.0) * 256.0);
    }
}
