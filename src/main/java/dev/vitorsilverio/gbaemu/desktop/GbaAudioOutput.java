package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.audio.GbaAudio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/// Streams the emulator's mixed PCM to the sound card. When a line is available its
/// blocking {@link SourceDataLine#write} doubles as the emulation clock: the producer
/// thread can only push samples as fast as the card plays them (32768 Hz), which paces
/// the whole emulator to real time without any sleep-based timing.
final class GbaAudioOutput implements AutoCloseable {
    private static final int SAMPLE_RATE = 32768;
    private static final int BYTES_PER_SAMPLE = 2;
    // ~250 ms of slack so an occasional GC/scheduling hiccup does not starve the card.
    private static final int LINE_BUFFER_SAMPLES = SAMPLE_RATE / 4;
    // Upper bound on how many samples a single pump moves (normally ~one frame's worth).
    private static final int MAX_DRAIN = SAMPLE_RATE / 8;

    private final GbaAudio audio;
    private final SourceDataLine line;
    private final byte[] outputBuffer = new byte[MAX_DRAIN * BYTES_PER_SAMPLE];

    private GbaAudioOutput(GbaAudio audio, SourceDataLine line) {
        this.audio = audio;
        this.line = line;
    }

    static GbaAudioOutput open(GbaAudio audio) {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, LINE_BUFFER_SAMPLES * BYTES_PER_SAMPLE);
            line.start();
            // Prime the buffer with silence so it starts (nearly) full. Otherwise the
            // emulation races at full speed to fill the empty buffer before write()
            // blocks, which fast-forwards the first ~250 ms (the BIOS intro visibly
            // outran the chime). Starting full means the first real pump() blocks,
            // pacing from frame 0. start() before write() avoids a deadlock if the
            // mixer rounded the buffer down (a stopped line could never drain).
            byte[] silence = new byte[LINE_BUFFER_SAMPLES * BYTES_PER_SAMPLE];
            line.write(silence, 0, silence.length);
            return new GbaAudioOutput(audio, line);
        } catch (LineUnavailableException | IllegalArgumentException exception) {
            return new GbaAudioOutput(audio, null);
        }
    }

    static GbaAudioOutput muted(GbaAudio audio) {
        return new GbaAudioOutput(audio, null);
    }

    /// True when a real sound line is driving playback, in which case {@link #pump()}
    /// blocks and acts as the timing source. When false the caller must pace itself.
    boolean isActive() {
        return line != null;
    }

    /// Discards whatever is queued on the sound card so a frozen emulator stops looping the
    /// last buffer ("static"). Safe to call repeatedly; playback resumes when pump() feeds it.
    void silence() {
        if (line != null) {
            line.flush();
        }
    }

    /// Drains every queued sample and writes it to the card. Blocks while the line
    /// buffer is full, which is exactly what keeps the emulator at real time.
    void pump() {
        byte[] samples = audio.drainPcm(MAX_DRAIN);
        if (line == null || samples.length == 0) {
            return;
        }
        for (int i = 0; i < samples.length; i++) {
            int value = samples[i] << 8;
            outputBuffer[i * 2] = (byte) value;
            outputBuffer[i * 2 + 1] = (byte) (value >>> 8);
        }
        line.write(outputBuffer, 0, samples.length * BYTES_PER_SAMPLE);
    }

    @Override
    public void close() {
        if (line != null) {
            // stop + flush unblocks any thread parked in write() so it can exit.
            line.stop();
            line.flush();
            line.close();
        }
    }
}
