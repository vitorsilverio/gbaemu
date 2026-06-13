package dev.vitorsilverio.gbaemu.audio;

import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/// Estado inicial dos registradores de som do GBA.
///
/// Ainda nao mistura samples, mas modela a superficie de I/O usada pela BIOS e
/// pelos jogos: registradores legiveis/escreviveis, SOUNDBIAS e FIFOs A/B.
public final class GbaAudio implements MemorySpace {
    public static final int SOUND_START = 0x04000060;
    public static final int SOUND_END = 0x040000A7;
    public static final int SOUND1CNT_L = 0x04000060;
    public static final int SOUND1CNT_H = 0x04000062;
    public static final int SOUND1CNT_X = 0x04000064;
    public static final int SOUND2CNT_L = 0x04000068;
    public static final int SOUND2CNT_H = 0x0400006C;
    public static final int SOUND3CNT_L = 0x04000070;
    public static final int SOUND3CNT_H = 0x04000072;
    public static final int SOUND3CNT_X = 0x04000074;
    public static final int SOUND4CNT_L = 0x04000078;
    public static final int SOUND4CNT_H = 0x0400007C;
    public static final int SOUNDCNT_L = 0x04000080;
    public static final int SOUNDCNT_H = 0x04000082;
    public static final int SOUNDCNT_X = 0x04000084;
    public static final int SOUNDBIAS = 0x04000088;
    public static final int WAVE_RAM = 0x04000090;
    public static final int FIFO_A = 0x040000A0;
    public static final int FIFO_B = 0x040000A4;
    public static final int FIFO_A_REQUEST = 1;
    public static final int FIFO_B_REQUEST = 1 << 1;

    private static final int REGISTERS_SIZE = SOUND_END - SOUND_START + 1;
    private static final int CPU_CLOCK_HZ = 16_777_216;
    private static final int OUTPUT_SAMPLE_RATE = 32_768;
    private static final int FIFO_CAPACITY = 32;
    private static final int FIFO_REFILL_LEVEL = 16;
    private static final int PCM_CAPACITY = OUTPUT_SAMPLE_RATE;
    private static final int DIRECT_SOUND_A_OUTPUT = 0x0300;
    private static final int DIRECT_SOUND_A_TIMER = 1 << 10;
    private static final int DIRECT_SOUND_A_RESET = 1 << 11;
    private static final int DIRECT_SOUND_A_VOLUME_100 = 1 << 2;
    private static final int DIRECT_SOUND_B_OUTPUT = 0x3000;
    private static final int DIRECT_SOUND_B_TIMER = 1 << 14;
    private static final int DIRECT_SOUND_B_RESET = 1 << 15;
    private static final int DIRECT_SOUND_B_VOLUME_100 = 1 << 3;
    // Scales the summed PSG channels (0..480 after master volume) down to the same
    // amplitude window as a Direct Sound channel so they do not swamp the mix.
    private static final int PSG_OUTPUT_DIVISOR = 4;
    // One-pole high-pass coefficient (~5 Hz cutoff at 32768 Hz) used to remove the
    // PSG DC offset; close to 1.0 so it only strips DC, not audible bass.
    private static final double PSG_HPF_DECAY = 0.999;
    private static final int[][] DUTY_PATTERNS = {
            {0, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 1, 1},
            {0, 1, 1, 1, 1, 1, 1, 0}
    };

    private final byte[] registers = new byte[REGISTERS_SIZE];
    private final Deque<Integer> fifoA = new ArrayDeque<>(FIFO_CAPACITY);
    private final Deque<Integer> fifoB = new ArrayDeque<>(FIFO_CAPACITY);
    private final Deque<Byte> pcm = new ArrayDeque<>(PCM_CAPACITY);
    private final PulseChannel channel1 = new PulseChannel(true);
    private final PulseChannel channel2 = new PulseChannel(false);
    private final WaveChannel channel3 = new WaveChannel();
    private final NoiseChannel channel4 = new NoiseChannel();
    // User-facing per-channel controls for the audio debug panel (not part of GBA state):
    // indices 0-3 are PSG CH1-CH4, 4/5 are Direct Sound A/B. Reset() must not clear these.
    private final boolean[] channelMuted = new boolean[6];
    private final int[] channelVolume = {100, 100, 100, 100, 100, 100};
    private int lastSampleA;
    private int lastSampleB;
    private double psgHpfPrevInput;
    private double psgHpfPrevOutput;
    private long sampleAccumulator;
    private long frameSequencerAccumulator;
    private int frameSequencerStep;

    public GbaAudio() {
        writeRaw16(SOUNDBIAS, 0x0200);
    }

    @Override
    public boolean contains(int address) {
        return address >= SOUND_START && address <= SOUND_END;
    }

    @Override
    public int readByte(int address) {
        return read8(address);
    }

    @Override
    public void writeByte(int address, int value) {
        write8(address, value & 0xFF);
    }

    @Override
    public void writeHalfWord(int address, int value) {
        write16(address & ~1, value & 0xFFFF);
    }

    @Override
    public void writeWord(int address, int value) {
        write32(address & ~3, value);
    }

    public int read8(int address) {
        if (isFifo(address)) {
            return 0;
        }
        if ((address & ~1) == SOUNDCNT_X) {
            return readSoundControlXByte(address);
        }
        return registers[offset(address)] & 0xFF;
    }

    public void write8(int address, int value) {
        int data = value & 0xFF;
        if (isFifo(address)) {
            pushFifo(address, data);
            return;
        }

        int aligned = address & ~1;
        int current = readRaw16(aligned);
        int next = ((address & 1) == 0)
                ? (current & 0xFF00) | data
                : (current & 0x00FF) | (data << 8);
        write16(aligned, next);
    }

    public void write16(int address, int value) {
        int aligned = address & ~1;
        int data = value & 0xFFFF;
        if (isFifo(aligned)) {
            pushFifo(aligned, data & 0xFF);
            pushFifo(aligned, data >>> 8);
            return;
        }

        switch (aligned) {
            case SOUNDCNT_H -> writeSoundControlH(data);
            case SOUNDCNT_X -> writeSoundControlX(data);
            case SOUNDBIAS -> writeRaw16(SOUNDBIAS, data & 0xC3FE);
            default -> writeSoundRegister(aligned, data);
        }
    }

    public void write32(int address, int value) {
        if (isFifo(address & ~3)) {
            pushFifo(address, value & 0xFF);
            pushFifo(address, value >>> 8);
            pushFifo(address, value >>> 16);
            pushFifo(address, value >>> 24);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    public int soundBias() {
        return readRaw16(SOUNDBIAS);
    }

    public boolean masterEnabled() {
        return (readRaw16(SOUNDCNT_X) & 0x80) != 0;
    }

    public boolean directSoundActive() {
        int control = readRaw16(SOUNDCNT_H);
        return masterEnabled() && (control & (DIRECT_SOUND_A_OUTPUT | DIRECT_SOUND_B_OUTPUT)) != 0;
    }

    public int fifoASize() {
        return fifoA.size();
    }

    public int fifoBSize() {
        return fifoB.size();
    }

    /// Mutes or unmutes one channel (1-6: CH1-CH4 PSG, 5/6 Direct Sound A/B). A user
    /// control for the debug panel; independent of the game's own enable bits.
    public void setChannelMuted(int channel, boolean muted) {
        channelMuted[channel - 1] = muted;
    }

    public boolean channelMuted(int channel) {
        return channelMuted[channel - 1];
    }

    /// Scales one channel's contribution to the mix by {@code percent} (0-100). Lets the
    /// user attenuate individual channels to isolate a problem source.
    public void setChannelVolume(int channel, int percent) {
        channelVolume[channel - 1] = Math.max(0, Math.min(100, percent));
    }

    public int channelVolume(int channel) {
        return channelVolume[channel - 1];
    }

    /// Builds a read-only view of the whole sound unit for the debug panel.
    public GbaAudioSnapshot debugSnapshot() {
        int control = readRaw16(SOUNDCNT_H);
        boolean enabledA = masterEnabled() && (control & DIRECT_SOUND_A_OUTPUT) != 0;
        boolean enabledB = masterEnabled() && (control & DIRECT_SOUND_B_OUTPUT) != 0;
        List<GbaAudioChannelSnapshot> channels = List.of(
                channel1.snapshot("CH1 Pulse", 1),
                channel2.snapshot("CH2 Pulse", 2),
                channel3.snapshot("CH3 Wave", 3),
                channel4.snapshot("CH4 Noise", 4),
                directSnapshot("Direct A", 5, enabledA, lastSampleA, fifoA.size()),
                directSnapshot("Direct B", 6, enabledB, lastSampleB, fifoB.size()));
        return new GbaAudioSnapshot(
                readRaw16(SOUNDCNT_L), control, readRaw16(SOUNDCNT_X), soundBias(),
                masterEnabled(), OUTPUT_SAMPLE_RATE, pcm.size(), fifoA.size(), fifoB.size(), channels);
    }

    private GbaAudioChannelSnapshot directSnapshot(String name, int channel, boolean enabled, int lastSample, int fifoSize) {
        return new GbaAudioChannelSnapshot(name, enabled, lastSample, 0.0,
                channelMuted[channel - 1], channelVolume[channel - 1],
                "fifo=" + fifoSize + " last=" + lastSample);
    }

    /// Applies the user's per-channel mute/volume to a channel's contribution.
    private int userScaled(int channel, int value) {
        if (channelMuted[channel - 1]) {
            return 0;
        }
        int volume = channelVolume[channel - 1];
        return volume == 100 ? value : value * volume / 100;
    }

    public void tick(int cycles) {
        if (cycles <= 0) {
            return;
        }
        if (!masterEnabled()) {
            queueSilence(cycles);
            return;
        }

        channel1.tick(cycles);
        channel2.tick(cycles);
        channel3.tick(cycles);
        channel4.tick(cycles);
        clockFrameSequencer(cycles);
        queueMixedSamples(cycles);
    }

    public int popFifoA() {
        return fifoA.isEmpty() ? 0 : fifoA.removeFirst();
    }

    public int popFifoB() {
        return fifoB.isEmpty() ? 0 : fifoB.removeFirst();
    }

    public int timerOverflow(int timerOverflowMask) {
        int timer0Overflows = (timerOverflowMask & 1) == 0 ? 0 : 1;
        int timer1Overflows = (timerOverflowMask & 2) == 0 ? 0 : 1;
        return timerOverflow(timerOverflowMask, timer0Overflows, timer1Overflows);
    }

    public int timerOverflow(int timerOverflowMask, int timer0Overflows, int timer1Overflows) {
        if (!masterEnabled()) {
            return 0;
        }
        int requestMask = 0;
        int control = readRaw16(SOUNDCNT_H);
        boolean enabledA = (control & DIRECT_SOUND_A_OUTPUT) != 0;
        boolean enabledB = (control & DIRECT_SOUND_B_OUTPUT) != 0;
        int timerA = (control & DIRECT_SOUND_A_TIMER) == 0 ? 0 : 1;
        int timerB = (control & DIRECT_SOUND_B_TIMER) == 0 ? 0 : 1;
        if (enabledA && (timerOverflowMask & (1 << timerA)) != 0) {
            int overflows = timerA == 0 ? timer0Overflows : timer1Overflows;
            for (int i = 0; i < overflows; i++) {
                nextDirectSoundSample(fifoA, true);
            }
            if (fifoA.size() <= FIFO_REFILL_LEVEL) {
                requestMask |= FIFO_A_REQUEST;
            }
        }
        if (enabledB && (timerOverflowMask & (1 << timerB)) != 0) {
            int overflows = timerB == 0 ? timer0Overflows : timer1Overflows;
            for (int i = 0; i < overflows; i++) {
                nextDirectSoundSample(fifoB, false);
            }
            if (fifoB.size() <= FIFO_REFILL_LEVEL) {
                requestMask |= FIFO_B_REQUEST;
            }
        }
        lastSampleA = clampSigned8(lastSampleA);
        lastSampleB = clampSigned8(lastSampleB);
        return requestMask;
    }

    public byte[] drainPcm(int maxSamples) {
        int count = Math.min(Math.max(maxSamples, 0), pcm.size());
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = pcm.removeFirst();
        }
        return out;
    }

    /// Serializes the sound register/mixer state into a save state. The transient FIFOs and
    /// the PCM output queue are not stored (they refill from DMA/mixing after a reload), and
    /// the user mute/volume preferences are left untouched (they are not GBA state).
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.write(registers);
        out.writeInt(lastSampleA);
        out.writeInt(lastSampleB);
        out.writeLong(sampleAccumulator);
        out.writeLong(frameSequencerAccumulator);
        out.writeInt(frameSequencerStep);
        out.writeDouble(psgHpfPrevInput);
        out.writeDouble(psgHpfPrevOutput);
    }

    /// Restores the sound register/mixer state from a save state and resets the channels +
    /// FIFOs so they re-derive from the restored registers (avoids stale in-flight notes).
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(registers);
        lastSampleA = in.readInt();
        lastSampleB = in.readInt();
        sampleAccumulator = in.readLong();
        frameSequencerAccumulator = in.readLong();
        frameSequencerStep = in.readInt();
        psgHpfPrevInput = in.readDouble();
        psgHpfPrevOutput = in.readDouble();
        fifoA.clear();
        fifoB.clear();
        pcm.clear();
        channel1.disable();
        channel2.disable();
        channel3.disable();
        channel4.disable();
    }

    private int readSoundControlXByte(int address) {
        int value = readRaw16(SOUNDCNT_X) & 0x0080;
        return ((address & 1) == 0) ? value : (value >>> 8);
    }

    private void writeSoundControlX(int value) {
        if ((value & 0x80) == 0) {
            clearSoundRegisters();
            return;
        }
        writeRaw16(SOUNDCNT_X, 0x80);
    }

    private void writeSoundControlH(int value) {
        if ((value & DIRECT_SOUND_A_RESET) != 0) {
            fifoA.clear();
            lastSampleA = 0;
        }
        if ((value & DIRECT_SOUND_B_RESET) != 0) {
            fifoB.clear();
            lastSampleB = 0;
        }
        writeRaw16(SOUNDCNT_H, value & ~(DIRECT_SOUND_A_RESET | DIRECT_SOUND_B_RESET));
    }

    private void clearSoundRegisters() {
        for (int address = SOUND_START; address <= SOUND_END; address++) {
            if (!isFifo(address)) {
                registers[offset(address)] = 0;
            }
        }
        fifoA.clear();
        fifoB.clear();
        lastSampleA = 0;
        lastSampleB = 0;
        psgHpfPrevInput = 0;
        psgHpfPrevOutput = 0;
        pcm.clear();
        sampleAccumulator = 0;
        frameSequencerAccumulator = 0;
        frameSequencerStep = 0;
        channel1.disable();
        channel2.disable();
        channel3.disable();
        channel4.disable();
        writeRaw16(SOUNDBIAS, 0x0200);
    }

    private boolean isFifo(int address) {
        int aligned = address & ~3;
        return aligned == FIFO_A || aligned == FIFO_B;
    }

    private void pushFifo(int address, int value) {
        Deque<Integer> fifo = ((address & ~3) == FIFO_B) ? fifoB : fifoA;
        if (fifo.size() == FIFO_CAPACITY) {
            fifo.removeFirst();
        }
        fifo.addLast(value & 0xFF);
    }

    private int nextDirectSoundSample(Deque<Integer> fifo, boolean channelA) {
        int sample;
        if (fifo.isEmpty()) {
            sample = channelA ? lastSampleA : lastSampleB;
        } else {
            sample = (byte) (int) fifo.removeFirst();
            if (channelA) {
                lastSampleA = sample;
            } else {
                lastSampleB = sample;
            }
        }
        return sample;
    }

    private void queuePcm(int sample) {
        if (pcm.size() == PCM_CAPACITY) {
            pcm.removeFirst();
        }
        pcm.addLast((byte) clampSigned8(sample));
    }

    private void queueSilence(int cycles) {
        sampleAccumulator += cycles * OUTPUT_SAMPLE_RATE;
        while (sampleAccumulator >= CPU_CLOCK_HZ) {
            sampleAccumulator -= CPU_CLOCK_HZ;
            queuePcm(0);
        }
    }

    private void queueMixedSamples(int cycles) {
        sampleAccumulator += cycles * OUTPUT_SAMPLE_RATE;
        while (sampleAccumulator >= CPU_CLOCK_HZ) {
            sampleAccumulator -= CPU_CLOCK_HZ;
            queuePcm(mixSample());
        }
    }

    private int mixSample() {
        int soundCntL = readRaw16(SOUNDCNT_L);
        int soundCntH = readRaw16(SOUNDCNT_H);
        int leftVolume = ((soundCntL >>> 4) & 0x7) + 1;
        int rightVolume = (soundCntL & 0x7) + 1;
        int psgShift = switch (soundCntH & 0x3) {
            case 0 -> 2;  // 25%
            case 1 -> 1;  // 50%
            default -> 0; // 100%
        };

        int ch1 = userScaled(1, channel1.output());
        int ch2 = userScaled(2, channel2.output());
        int ch3 = userScaled(3, channel3.output());
        int ch4 = userScaled(4, channel4.output());
        int psgLeft = 0;
        int psgRight = 0;
        if ((soundCntL & 0x0100) != 0) psgRight += ch1;
        if ((soundCntL & 0x0200) != 0) psgRight += ch2;
        if ((soundCntL & 0x0400) != 0) psgRight += ch3;
        if ((soundCntL & 0x0800) != 0) psgRight += ch4;
        if ((soundCntL & 0x1000) != 0) psgLeft += ch1;
        if ((soundCntL & 0x2000) != 0) psgLeft += ch2;
        if ((soundCntL & 0x4000) != 0) psgLeft += ch3;
        if ((soundCntL & 0x8000) != 0) psgLeft += ch4;
        // PSG channels carry a DC offset while active; the high-pass filter strips it
        // so idle/held channels stop biasing (and clipping) the whole mix.
        int psgMono = (((psgLeft * leftVolume) >> psgShift) + ((psgRight * rightVolume) >> psgShift)) / 2;
        int psgMix = highPassPsg(psgMono / PSG_OUTPUT_DIVISOR);

        int directLeft = 0;
        int directRight = 0;
        int sampleA = userScaled(5, scaleDirectSound(lastSampleA, (soundCntH & DIRECT_SOUND_A_VOLUME_100) != 0));
        int sampleB = userScaled(6, scaleDirectSound(lastSampleB, (soundCntH & DIRECT_SOUND_B_VOLUME_100) != 0));
        if ((soundCntH & 0x0100) != 0) directRight += sampleA;
        if ((soundCntH & 0x0200) != 0) directLeft += sampleA;
        if ((soundCntH & 0x1000) != 0) directRight += sampleB;
        if ((soundCntH & 0x2000) != 0) directLeft += sampleB;
        int directMix = (directLeft + directRight) / 2;
        return directMix + psgMix;
    }

    /// One-pole high-pass filter modelling the GBA's AC-coupled PSG output: it
    /// removes the constant bias an active square/wave/noise channel holds at its
    /// idle DAC level, which would otherwise clip the mix to a railed value.
    private int highPassPsg(int input) {
        double output = input - psgHpfPrevInput + PSG_HPF_DECAY * psgHpfPrevOutput;
        psgHpfPrevInput = input;
        psgHpfPrevOutput = output;
        return (int) output;
    }

    private void clockFrameSequencer(int cycles) {
        frameSequencerAccumulator += cycles * 512;
        while (frameSequencerAccumulator >= CPU_CLOCK_HZ) {
            frameSequencerAccumulator -= CPU_CLOCK_HZ;
            if ((frameSequencerStep & 1) == 0) {
                channel1.tickLength();
                channel2.tickLength();
                channel3.tickLength();
                channel4.tickLength();
            }
            if (frameSequencerStep == 2 || frameSequencerStep == 6) {
                channel1.tickSweep();
            }
            if (frameSequencerStep == 7) {
                channel1.tickEnvelope();
                channel2.tickEnvelope();
                channel4.tickEnvelope();
            }
            frameSequencerStep = (frameSequencerStep + 1) & 7;
        }
    }

    private void writeSoundRegister(int aligned, int data) {
        int oldValue = readRaw16(aligned);
        writeRaw16(aligned, data);
        switch (aligned) {
            case SOUND1CNT_L -> channel1.setSweep(oldValue, data);
            case SOUND1CNT_H -> {
                channel1.setLength(64 - (data & 0x3F));
                channel1.setEnvelope(data >>> 8);
            }
            case SOUND1CNT_X -> {
                channel1.updatePeriod();
                if ((data & 0x8000) != 0) {
                    channel1.trigger();
                }
            }
            case SOUND2CNT_L -> {
                channel2.setLength(64 - (data & 0x3F));
                channel2.setEnvelope(data >>> 8);
            }
            case SOUND2CNT_H -> {
                channel2.updatePeriod();
                if ((data & 0x8000) != 0) {
                    channel2.trigger();
                }
            }
            case SOUND3CNT_L -> {
                if ((data & 0x0080) == 0) {
                    channel3.disable();
                }
            }
            case SOUND3CNT_H -> channel3.lengthTimer = 256 - (data & 0xFF);
            case SOUND3CNT_X -> {
                channel3.updatePeriod();
                if ((data & 0x8000) != 0) {
                    channel3.trigger();
                }
            }
            case SOUND4CNT_L -> {
                channel4.lengthTimer = 64 - (data & 0x3F);
                channel4.setEnvelope(data >>> 8);
            }
            case SOUND4CNT_H -> {
                if ((data & 0x8000) != 0) {
                    channel4.trigger();
                }
            }
            default -> {
            }
        }
    }

    private int wavePatternRam(int offset) {
        return read8(WAVE_RAM + (offset & 0x0F));
    }

    private static int scaleDirectSound(int sample, boolean fullVolume) {
        return fullVolume ? sample : sample / 2;
    }

    private static int clampSigned8(int sample) {
        return Math.max(-128, Math.min(127, sample));
    }

    private int readRaw16(int address) {
        int low = registers[offset(address)] & 0xFF;
        int high = registers[offset(address + 1)] & 0xFF;
        return low | (high << 8);
    }

    private void writeRaw16(int address, int value) {
        registers[offset(address)] = (byte) value;
        registers[offset(address + 1)] = (byte) (value >>> 8);
    }

    private static int offset(int address) {
        return address - SOUND_START;
    }

    private abstract static class SoundChannel {
        boolean enabled;
        int lengthTimer;
        int currentVolume;
        int envelopeTimer;
        int timer;

        void setEnvelope(int value) {
            if ((value & 0xF8) == 0) {
                enabled = false;
            }
        }

        void triggerEnvelope(int value) {
            currentVolume = (value >>> 4) & 0x0F;
            envelopeTimer = value & 0x07;
        }

        void tickEnvelope(int value) {
            int pace = value & 0x07;
            if (!enabled || pace == 0) {
                return;
            }
            envelopeTimer--;
            if (envelopeTimer > 0) {
                return;
            }
            envelopeTimer = pace;
            if ((value & 0x08) != 0 && currentVolume < 15) {
                currentVolume++;
            } else if ((value & 0x08) == 0 && currentVolume > 0) {
                currentVolume--;
            }
        }

        void tickLength(int control) {
            if ((control & 0x4000) == 0 || lengthTimer <= 0) {
                return;
            }
            lengthTimer--;
            if (lengthTimer == 0) {
                enabled = false;
            }
        }

        void disable() {
            enabled = false;
            lengthTimer = 0;
            currentVolume = 0;
            envelopeTimer = 0;
            timer = 0;
        }

        abstract void tick(int cycles);

        /// Digital DAC level in 0..15 (0 when the channel is silent/disabled).
        /// The DC offset of an active waveform is removed by the mixer's high-pass
        /// filter, mirroring the GBA's AC-coupled output.
        abstract int output();
    }

    private final class PulseChannel extends SoundChannel {
        private final boolean sweepChannel;
        private int period;
        private int dutyStep;
        private int sweepShadowPeriod;
        private int sweepTimer;
        private boolean sweepEnabled;
        private boolean sweepNegateUsed;

        PulseChannel(boolean sweepChannel) {
            this.sweepChannel = sweepChannel;
        }

        void setLength(int length) {
            lengthTimer = length == 0 ? 64 : length;
        }

        void setSweep(int oldValue, int newValue) {
            if (!sweepChannel) {
                return;
            }
            boolean wasNegate = (oldValue & 0x0008) != 0;
            boolean isNegate = (newValue & 0x0008) != 0;
            if (wasNegate && !isNegate && sweepNegateUsed) {
                enabled = false;
            }
        }

        void updatePeriod() {
            period = readRaw16(sweepChannel ? SOUND1CNT_X : SOUND2CNT_H) & 0x07FF;
        }

        void trigger() {
            int envelope = sweepChannel ? readRaw16(SOUND1CNT_H) >>> 8 : readRaw16(SOUND2CNT_L) >>> 8;
            if (lengthTimer == 0) {
                lengthTimer = 64;
            }
            boolean sweepAllowsChannel = triggerSweep();
            enabled = (envelope & 0xF8) != 0 && sweepAllowsChannel;
            triggerEnvelope(envelope);
            timer = Math.max(4, pulseTimerPeriod());
        }

        @Override
        void tick(int cycles) {
            if (!enabled) {
                return;
            }
            timer -= cycles;
            if (timer > 0) {
                return;
            }
            int periodCycles = pulseTimerPeriod();
            int steps = 1 + (-timer / periodCycles);
            dutyStep = (dutyStep + steps) & 0x07;
            timer += steps * periodCycles;
        }

        @Override
        int output() {
            int envelope = sweepChannel ? readRaw16(SOUND1CNT_H) >>> 8 : readRaw16(SOUND2CNT_L) >>> 8;
            if ((envelope & 0xF8) == 0 || !enabled) {
                return 0;
            }
            int duty = (sweepChannel ? readRaw16(SOUND1CNT_H) : readRaw16(SOUND2CNT_L)) >>> 14;
            return DUTY_PATTERNS[duty & 3][dutyStep] == 0 ? 0 : currentVolume;
        }

        GbaAudioChannelSnapshot snapshot(String name, int channel) {
            int duty = (sweepChannel ? readRaw16(SOUND1CNT_H) : readRaw16(SOUND2CNT_L)) >>> 14;
            double freq = period >= 2048 ? 0.0 : 131072.0 / (2048 - period);
            return new GbaAudioChannelSnapshot(name, enabled, currentVolume, freq,
                    channelMuted[channel - 1], channelVolume[channel - 1],
                    String.format("duty=%d period=%d", duty & 3, period));
        }

        void tickLength() {
            tickLength(sweepChannel ? readRaw16(SOUND1CNT_X) : readRaw16(SOUND2CNT_H));
        }

        void tickEnvelope() {
            tickEnvelope(sweepChannel ? readRaw16(SOUND1CNT_H) >>> 8 : readRaw16(SOUND2CNT_L) >>> 8);
        }

        private int pulseTimerPeriod() {
            // Cycles per duty step on the GBA's 16.78 MHz clock: a full 8-step wave is
            // 131072/(2048-period) Hz, so one step is 16*(2048-period) CPU cycles. (The
            // classic Game Boy value is 4*(2048-period) T-cycles; the GBA CPU runs 4x
            // faster, hence the x4 — without it the channel played two octaves too high.)
            return Math.max(16, (2048 - period) * 16);
        }

        private boolean triggerSweep() {
            if (!sweepChannel) {
                return true;
            }
            sweepShadowPeriod = period;
            sweepTimer = sweepPace();
            if (sweepTimer == 0) {
                sweepTimer = 8;
            }
            sweepEnabled = sweepPace() != 0 || sweepStep() != 0;
            sweepNegateUsed = false;
            return sweepStep() == 0 || calculateSweepPeriod() <= 0x7FF;
        }

        void tickSweep() {
            if (!sweepEnabled || !enabled) {
                return;
            }
            sweepTimer--;
            if (sweepTimer > 0) {
                return;
            }
            sweepTimer = sweepPace();
            if (sweepTimer == 0) {
                sweepTimer = 8;
            }
            if (sweepPace() == 0) {
                return;
            }
            int calculatedPeriod = calculateSweepPeriod();
            if (calculatedPeriod > 0x7FF) {
                enabled = false;
                return;
            }
            if (sweepStep() == 0) {
                return;
            }
            sweepShadowPeriod = calculatedPeriod;
            period = calculatedPeriod;
            int control = readRaw16(SOUND1CNT_X) & 0xF800;
            writeRaw16(SOUND1CNT_X, control | (calculatedPeriod & 0x07FF));
            if (calculateSweepPeriod() > 0x7FF) {
                enabled = false;
            }
        }

        private int calculateSweepPeriod() {
            int delta = sweepShadowPeriod >> sweepStep();
            if ((readRaw16(SOUND1CNT_L) & 0x0008) != 0) {
                sweepNegateUsed = true;
                return (sweepShadowPeriod - delta) & 0x7FF;
            }
            return sweepShadowPeriod + delta;
        }

        private int sweepPace() {
            return (readRaw16(SOUND1CNT_L) >>> 4) & 0x07;
        }

        private int sweepStep() {
            return readRaw16(SOUND1CNT_L) & 0x07;
        }
    }

    private final class WaveChannel extends SoundChannel {
        private int period;
        private int sampleIndex;
        private int lastSample;

        void updatePeriod() {
            period = readRaw16(SOUND3CNT_X) & 0x07FF;
        }

        void trigger() {
            if (lengthTimer == 0) {
                lengthTimer = 256;
            }
            enabled = (readRaw16(SOUND3CNT_L) & 0x0080) != 0;
            timer = waveTimerPeriod() + 6;
            sampleIndex = 0;
            lastSample = readWaveSample();
        }

        @Override
        void tick(int cycles) {
            if (!enabled) {
                return;
            }
            timer -= cycles;
            if (timer > 0) {
                return;
            }
            int periodCycles = waveTimerPeriod();
            int steps = 1 + (-timer / periodCycles);
            sampleIndex = (sampleIndex + steps) & 0x1F;
            lastSample = readWaveSample();
            timer += steps * periodCycles;
        }

        @Override
        int output() {
            if (!enabled || (readRaw16(SOUND3CNT_L) & 0x0080) == 0) {
                return 0;
            }
            int volumeCode = (readRaw16(SOUND3CNT_H) >>> 13) & 0x03;
            return switch (volumeCode) {
                case 0 -> 0;
                case 1 -> lastSample;
                case 2 -> lastSample >> 1;
                case 3 -> lastSample >> 2;
                default -> 0;
            };
        }

        GbaAudioChannelSnapshot snapshot(String name, int channel) {
            double freq = period >= 2048 ? 0.0 : 65536.0 / (2048 - period);
            int volumeCode = (readRaw16(SOUND3CNT_H) >>> 13) & 0x03;
            return new GbaAudioChannelSnapshot(name, enabled, lastSample, freq,
                    channelMuted[channel - 1], channelVolume[channel - 1],
                    String.format("volCode=%d idx=%d", volumeCode, sampleIndex));
        }

        void tickLength() {
            tickLength(readRaw16(SOUND3CNT_X));
        }

        private int waveTimerPeriod() {
            // Cycles per 4-bit sample on the GBA clock = 8*(2048-period); the full 32-sample
            // wave is 65536/(2048-period) Hz. (Game Boy value 2*(2048-period) T-cycles x4 for
            // the 4x-faster GBA CPU clock.)
            return Math.max(8, (2048 - period) * 8);
        }

        private int readWaveSample() {
            int packed = wavePatternRam(sampleIndex >> 1);
            return (sampleIndex & 1) == 0 ? packed >>> 4 : packed & 0x0F;
        }
    }

    private final class NoiseChannel extends SoundChannel {
        private int lfsr;

        void trigger() {
            if (lengthTimer == 0) {
                lengthTimer = 64;
            }
            int envelope = readRaw16(SOUND4CNT_L) >>> 8;
            enabled = (envelope & 0xF8) != 0;
            lfsr = 0;
            triggerEnvelope(envelope);
            timer = noiseTimerPeriod();
        }

        @Override
        void tick(int cycles) {
            if (!enabled) {
                return;
            }
            timer -= cycles;
            int periodCycles = noiseTimerPeriod();
            while (timer <= 0) {
                timer += periodCycles;
                if (((readRaw16(SOUND4CNT_H) >>> 4) & 0x0F) >= 14) {
                    continue;
                }
                int nextBit = ((lfsr & 1) ^ ((lfsr >>> 1) & 1)) ^ 1;
                lfsr = (lfsr >>> 1) | (nextBit << 14);
                if ((readRaw16(SOUND4CNT_H) & 0x0008) != 0) {
                    lfsr = (lfsr & ~(1 << 6)) | (nextBit << 6);
                }
            }
        }

        @Override
        int output() {
            int envelope = readRaw16(SOUND4CNT_L) >>> 8;
            if (!enabled || (envelope & 0xF8) == 0) {
                return 0;
            }
            return (lfsr & 1) == 0 ? currentVolume : 0;
        }

        GbaAudioChannelSnapshot snapshot(String name, int channel) {
            int value = readRaw16(SOUND4CNT_H) & 0xFF;
            int divisorCode = value & 0x07;
            int shift = (value >>> 4) & 0x0F;
            double divisor = divisorCode == 0 ? 8.0 : divisorCode * 16.0;
            double freq = shift >= 14 ? 0.0 : 524288.0 / divisor / (1 << (shift + 1));
            return new GbaAudioChannelSnapshot(name, enabled, currentVolume, freq,
                    channelMuted[channel - 1], channelVolume[channel - 1],
                    String.format("div=%d shift=%d", divisorCode, shift));
        }

        void tickLength() {
            tickLength(readRaw16(SOUND4CNT_H));
        }

        void tickEnvelope() {
            tickEnvelope(readRaw16(SOUND4CNT_L) >>> 8);
        }

        private int noiseTimerPeriod() {
            int value = readRaw16(SOUND4CNT_H) & 0xFF;
            int divisorCode = value & 0x07;
            int divisor = divisorCode == 0 ? 8 : divisorCode * 16;
            int shift = (value >>> 4) & 0x0F;
            // (divisor<<shift) is the Game Boy T-cycle period; x4 converts it to the
            // GBA's 4x-faster CPU clock so the LFSR (noise pitch) advances at the right rate.
            return Math.max(32, (divisor << shift) * 4);
        }
    }
}
