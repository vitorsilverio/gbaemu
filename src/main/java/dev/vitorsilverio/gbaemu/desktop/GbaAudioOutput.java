package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.audio.GbaAudio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

final class GbaAudioOutput implements AutoCloseable {
    private static final int SAMPLE_RATE = 32768;
    private static final int SAMPLES_PER_PUMP = SAMPLE_RATE / 60;
    private static final int BYTES_PER_SAMPLE = 2;

    private final GbaAudio audio;
    private final SourceDataLine line;
    private final byte[] outputBuffer = new byte[SAMPLES_PER_PUMP * BYTES_PER_SAMPLE];
    private byte lastOutputSample;

    private GbaAudioOutput(GbaAudio audio, SourceDataLine line) {
        this.audio = audio;
        this.line = line;
    }

    static GbaAudioOutput open(GbaAudio audio) {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, SAMPLE_RATE * BYTES_PER_SAMPLE);
            line.start();
            return new GbaAudioOutput(audio, line);
        } catch (LineUnavailableException | IllegalArgumentException exception) {
            return new GbaAudioOutput(audio, null);
        }
    }

    static GbaAudioOutput muted(GbaAudio audio) {
        return new GbaAudioOutput(audio, null);
    }

    void pump() {
        if (line == null) {
            audio.drainPcm(SAMPLES_PER_PUMP);
            return;
        }
        byte[] samples = audio.drainPcm(SAMPLES_PER_PUMP);
        int sampleIndex = 0;
        for (; sampleIndex < samples.length; sampleIndex++) {
            lastOutputSample = samples[sampleIndex];
            writePcm16(sampleIndex, lastOutputSample);
        }
        byte fillSample = audio.directSoundActive() ? lastOutputSample : 0;
        for (; sampleIndex < SAMPLES_PER_PUMP; sampleIndex++) {
            writePcm16(sampleIndex, fillSample);
        }
        line.write(outputBuffer, 0, outputBuffer.length);
    }

    private void writePcm16(int sampleIndex, int sample) {
        int value = sample << 8;
        int offset = sampleIndex * BYTES_PER_SAMPLE;
        outputBuffer[offset] = (byte) value;
        outputBuffer[offset + 1] = (byte) (value >>> 8);
    }

    @Override
    public void close() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
        }
    }
}
