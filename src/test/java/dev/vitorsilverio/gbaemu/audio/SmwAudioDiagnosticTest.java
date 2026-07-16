package dev.vitorsilverio.gbaemu.audio;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.input.GbaButton;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Opt-in real-ROM audio diagnostic for task D3 (SMW "chiado"/crackle report, see memory
/// `gba-c6-gameplay-findings`). Run with -Daudio.diag=1; skipped otherwise (and skipped if the
/// ROM is absent). Runs SMW through the same per-frame loop as {@link AudioDiagnosticTest} but
/// additionally solos each of the 6 mix channels (via {@link GbaAudio#setChannelMuted}) into its
/// own WAV so the crackle's source channel can be identified by ear/waveform.
class SmwAudioDiagnosticTest {
    private static final int SAMPLE_RATE = 32768;
    private static final int SAMPLES_PER_PUMP = SAMPLE_RATE / 60;
    private static final int CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;
    private static final String[] CHANNEL_NAMES = {
            "ch1-pulse", "ch2-pulse", "ch3-wave", "ch4-noise", "directA", "directB"
    };

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void smwAudioFullMix() throws Exception {
        Path romPath = Path.of("smw.gba");
        assumeTrue(Files.exists(romPath), "smw.gba not present");
        GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));

        int totalFrames = 900; // 15 emulated seconds, well past the title screen
        Metrics m = capture(console, totalFrames);
        writeWav(Path.of("target", "smw-audio-full.wav"), m.pcm.toByteArray());
        System.out.printf(
                "SMW full mix: railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void smwAudioPerChannelSolo() throws Exception {
        Path romPath = Path.of("smw.gba");
        assumeTrue(Files.exists(romPath), "smw.gba not present");
        int totalFrames = 900;

        for (int channel = 1; channel <= 6; channel++) {
            GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));
            for (int other = 1; other <= 6; other++) {
                console.audio().setChannelMuted(other, other != channel);
            }
            Metrics m = capture(console, totalFrames);
            String name = CHANNEL_NAMES[channel - 1];
            writeWav(Path.of("target", "smw-audio-solo-" + name + ".wav"), m.pcm.toByteArray());
            System.out.printf(
                    "SMW solo %-10s railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                    name, m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void smwAudioInGameplay() throws Exception {
        Path romPath = Path.of("smw.gba");
        assumeTrue(Files.exists(romPath), "smw.gba not present");
        GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));

        int totalFrames = 3600; // 60 emulated seconds: auto-mash through menus into gameplay
        Metrics m = new Metrics();
        byte prev = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            driveAutoMasher(console, frame);
            console.runCycles(CYCLES_PER_FRAME);
            for (byte b : console.audio().drainPcm(SAMPLES_PER_PUMP)) {
                if (b == 127 || b == -128) m.railed++;
                if (Math.abs(b - prev) > 100) m.clicks++;
                if (b < m.min) m.min = b;
                if (b > m.max) m.max = b;
                prev = b;
                m.total++;
                int v = b << 8;
                m.pcm.write(v & 0xFF);
                m.pcm.write((v >>> 8) & 0xFF);
            }
        }
        writeWav(Path.of("target", "smw-audio-gameplay.wav"), m.pcm.toByteArray());
        System.out.printf(
                "SMW gameplay (auto-mash): railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void smwAudioGameplayPerChannelSolo() throws Exception {
        Path romPath = Path.of("smw.gba");
        assumeTrue(Files.exists(romPath), "smw.gba not present");
        int totalFrames = 3600;

        for (int channel = 1; channel <= 6; channel++) {
            GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));
            for (int other = 1; other <= 6; other++) {
                console.audio().setChannelMuted(other, other != channel);
            }
            Metrics m = new Metrics();
            byte prev = 0;
            for (int frame = 0; frame < totalFrames; frame++) {
                driveAutoMasher(console, frame);
                console.runCycles(CYCLES_PER_FRAME);
                for (byte b : console.audio().drainPcm(SAMPLES_PER_PUMP)) {
                    if (b == 127 || b == -128) m.railed++;
                    if (Math.abs(b - prev) > 100) m.clicks++;
                    if (b < m.min) m.min = b;
                    if (b > m.max) m.max = b;
                    prev = b;
                    m.total++;
                    int v = b << 8;
                    m.pcm.write(v & 0xFF);
                    m.pcm.write((v >>> 8) & 0xFF);
                }
            }
            String name = CHANNEL_NAMES[channel - 1];
            writeWav(Path.of("target", "smw-audio-gameplay-solo-" + name + ".wav"), m.pcm.toByteArray());
            System.out.printf(
                    "SMW gameplay solo %-10s railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                    name, m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
        }
    }

    /// Presses START/A on a duty cycle to clear title/file-select/message boxes headlessly,
    /// then holds RIGHT so Mario walks (jump SFX etc. exercise the PSG channels).
    private static void driveAutoMasher(GbaConsole console, int frame) {
        var keypad = console.keypad();
        boolean menuPhase = frame < 1800; // first 30s: mash through menus
        if (menuPhase) {
            boolean pressed = (frame / 20) % 2 == 0;
            keypad.setPressed(GbaButton.START, pressed);
            keypad.setPressed(GbaButton.A, !pressed);
        } else {
            keypad.setPressed(GbaButton.START, false);
            keypad.setPressed(GbaButton.A, (frame / 90) % 3 == 0); // occasional jump
            keypad.setPressed(GbaButton.RIGHT, true);
        }
    }

    private static Metrics capture(GbaConsole console, int totalFrames) {
        Metrics m = new Metrics();
        byte prev = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            console.runCycles(CYCLES_PER_FRAME);
            for (byte b : console.audio().drainPcm(SAMPLES_PER_PUMP)) {
                if (b == 127 || b == -128) m.railed++;
                if (Math.abs(b - prev) > 100) m.clicks++;
                if (b < m.min) m.min = b;
                if (b > m.max) m.max = b;
                prev = b;
                m.total++;
                int v = b << 8;
                m.pcm.write(v & 0xFF);
                m.pcm.write((v >>> 8) & 0xFF);
            }
        }
        return m;
    }

    private static final class Metrics {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long railed;
        long clicks;
        long total;
        int min = 127;
        int max = -128;

        double railedFraction() { return total == 0 ? 0 : (double) railed / total; }
        double clickFraction() { return total == 0 ? 0 : (double) clicks / total; }
    }

    private static void writeWav(Path path, byte[] pcm16le) throws Exception {
        int dataLen = pcm16le.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeLe32(out, 36 + dataLen);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);                 // PCM
        writeLe16(out, 1);                 // mono
        writeLe32(out, SAMPLE_RATE);
        writeLe32(out, SAMPLE_RATE * 2);   // byte rate
        writeLe16(out, 2);                 // block align
        writeLe16(out, 16);                // bits per sample
        writeAscii(out, "data");
        writeLe32(out, dataLen);
        out.write(pcm16le);
        Files.createDirectories(path.getParent());
        Files.write(path, out.toByteArray());
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (int i = 0; i < s.length(); i++) out.write(s.charAt(i));
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }
}
