package dev.vitorsilverio.gbaemu.audio;

import java.util.ArrayDeque;
import java.util.Deque;

/// Estado inicial dos registradores de som do GBA.
///
/// Ainda nao mistura samples, mas modela a superficie de I/O usada pela BIOS e
/// pelos jogos: registradores legiveis/escreviveis, SOUNDBIAS e FIFOs A/B.
public final class GbaAudio {
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

    private static final int CPU_CLOCK_HZ = 16_777_216;
    private static final int OUTPUT_SAMPLE_RATE = 32_768;
    private static final int IO_BASE = 0x04000000;
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
    private static final int[][] DUTY_PATTERNS = {
            {0, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 1, 1},
            {0, 1, 1, 1, 1, 1, 1, 0}
    };

    private final byte[] io;
    private final Deque<Integer> fifoA = new ArrayDeque<>(FIFO_CAPACITY);
    private final Deque<Integer> fifoB = new ArrayDeque<>(FIFO_CAPACITY);
    private final Deque<Byte> pcm = new ArrayDeque<>(PCM_CAPACITY);
    private final PulseChannel channel1 = new PulseChannel(true);
    private final PulseChannel channel2 = new PulseChannel(false);
    private final WaveChannel channel3 = new WaveChannel();
    private final NoiseChannel channel4 = new NoiseChannel();
    private int lastSampleA;
    private int lastSampleB;
    private long sampleAccumulator;
    private long frameSequencerAccumulator;
    private int frameSequencerStep;

    public GbaAudio(byte[] io) {
        this.io = io;
        writeRaw16(SOUNDBIAS, 0x0200);
    }

    public boolean handles(int address) {
        return address >= SOUND_START && address <= SOUND_END;
    }

    public int read8(int address) {
        if (isFifo(address)) {
            return 0;
        }
        if ((address & ~1) == SOUNDCNT_X) {
            return readSoundControlXByte(address);
        }
        return io[offset(address)] & 0xFF;
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
                io[offset(address)] = 0;
            }
        }
        fifoA.clear();
        fifoB.clear();
        lastSampleA = 0;
        lastSampleB = 0;
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
        int psgLevel = switch (soundCntH & 0x3) {
            case 0 -> 2;
            case 1 -> 1;
            case 2 -> 0;
            default -> 0;
        };

        int ch1 = channel1.output();
        int ch2 = channel2.output();
        int ch3 = channel3.output();
        int ch4 = channel4.output();
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

        int directLeft = 0;
        int directRight = 0;
        int sampleA = scaleDirectSound(lastSampleA, (soundCntH & DIRECT_SOUND_A_VOLUME_100) != 0);
        int sampleB = scaleDirectSound(lastSampleB, (soundCntH & DIRECT_SOUND_B_VOLUME_100) != 0);
        if ((soundCntH & 0x0100) != 0) directRight += sampleA;
        if ((soundCntH & 0x0200) != 0) directLeft += sampleA;
        if ((soundCntH & 0x1000) != 0) directRight += sampleB;
        if ((soundCntH & 0x2000) != 0) directLeft += sampleB;

        int left = directLeft + (psgLeft * leftVolume >> psgLevel);
        int right = directRight + (psgRight * rightVolume >> psgLevel);
        return (left + right) / 2;
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
        int low = io[offset(address)] & 0xFF;
        int high = io[offset(address + 1)] & 0xFF;
        return low | (high << 8);
    }

    private void writeRaw16(int address, int value) {
        io[offset(address)] = (byte) value;
        io[offset(address + 1)] = (byte) (value >>> 8);
    }

    private static int offset(int address) {
        return address - IO_BASE;
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

        int dacOutput(int digitalOutput) {
            return 15 - digitalOutput * 2;
        }

        abstract void tick(int cycles);

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
            int digital = DUTY_PATTERNS[duty & 3][dutyStep] == 0 ? 0 : currentVolume;
            return dacOutput(digital);
        }

        void tickLength() {
            tickLength(sweepChannel ? readRaw16(SOUND1CNT_X) : readRaw16(SOUND2CNT_H));
        }

        void tickEnvelope() {
            tickEnvelope(sweepChannel ? readRaw16(SOUND1CNT_H) >>> 8 : readRaw16(SOUND2CNT_L) >>> 8);
        }

        private int pulseTimerPeriod() {
            return Math.max(4, (2048 - period) * 4);
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
            int digital = switch (volumeCode) {
                case 0 -> 0;
                case 1 -> lastSample;
                case 2 -> lastSample >> 1;
                case 3 -> lastSample >> 2;
                default -> 0;
            };
            return dacOutput(digital);
        }

        void tickLength() {
            tickLength(readRaw16(SOUND3CNT_X));
        }

        private int waveTimerPeriod() {
            return Math.max(2, (2048 - period) * 2);
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
            int digital = (lfsr & 1) == 0 ? currentVolume : 0;
            return dacOutput(digital);
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
            return Math.max(8, divisor << shift);
        }
    }
}
