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

/// Opt-in real-ROM audio diagnostic for task D4 (Metroid Fusion melody channel reported quiet,
/// out of sync, sped up and intermittent — see memory `gba-c6-gameplay-findings`). Run with
/// -Daudio.diag=1; skipped otherwise (and skipped if the ROM is absent). Mirrors the D3
/// (SmwAudioDiagnosticTest) approach: per-channel solo WAVs, plus the FIFO diagnostics added to
/// {@link GbaAudio}/{@code GbaTimerController} to test the "DirectSound FIFO/timer" hypothesis
/// from the task spec (batched timer overflows discarding samples, or FIFO underrun repeating
/// the last byte).
class MetroidAudioDiagnosticTest {
    private static final int SAMPLE_RATE = 32768;
    private static final int SAMPLES_PER_PUMP = SAMPLE_RATE / 60;
    private static final int CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;
    private static final int DIAGNOSTIC_WINDOW_FRAMES = 300; // 5s
    private static final String[] CHANNEL_NAMES = {
            "ch1-pulse", "ch2-pulse", "ch3-wave", "ch4-noise", "directA", "directB"
    };

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void metroidAudioGameplayWithFifoDiagnostics() throws Exception {
        Path romPath = Path.of("roms/metroid.gba");
        assumeTrue(Files.exists(romPath), "roms/metroid.gba not present");
        GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));

        int totalFrames = 3600; // 60 emulated seconds: auto-mash through menus/intro into gameplay
        Metrics m = new Metrics();
        byte prev = 0;
        console.audio().resetFifoDiagnostics();
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
            if ((frame + 1) % DIAGNOSTIC_WINDOW_FRAMES == 0) {
                printFifoDiagnostics(console, (frame + 1) / 60);
                console.audio().resetFifoDiagnostics();
            }
        }
        writeWav(Path.of("target", "metroid-audio-gameplay.wav"), m.pcm.toByteArray());
        System.out.printf(
                "Metroid gameplay (auto-mash): railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void metroidAudioGameplayPerChannelSolo() throws Exception {
        Path romPath = Path.of("roms/metroid.gba");
        assumeTrue(Files.exists(romPath), "roms/metroid.gba not present");
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
            writeWav(Path.of("target", "metroid-audio-gameplay-solo-" + name + ".wav"), m.pcm.toByteArray());
            System.out.printf(
                    "Metroid gameplay solo %-10s railed=%.4f click=%.4f min=%d max=%d total=%d%n",
                    name, m.railedFraction(), m.clickFraction(), m.min, m.max, m.total);
        }
    }

    /// Presses START/A on a duty cycle to clear title/file-select/intro cutscene headlessly,
    /// then holds RIGHT with occasional jump/shoot so the melody plays over real gameplay.
    private static void driveAutoMasher(GbaConsole console, int frame) {
        var keypad = console.keypad();
        boolean menuPhase = frame < 1800; // first 30s: mash through menus/cutscene
        if (menuPhase) {
            boolean pressed = (frame / 20) % 2 == 0;
            keypad.setPressed(GbaButton.START, pressed);
            keypad.setPressed(GbaButton.A, !pressed);
        } else {
            keypad.setPressed(GbaButton.START, false);
            keypad.setPressed(GbaButton.A, (frame / 90) % 3 == 0); // occasional jump
            keypad.setPressed(GbaButton.B, (frame / 60) % 4 == 0); // occasional shot
            keypad.setPressed(GbaButton.RIGHT, true);
        }
    }

    private static void printFifoDiagnostics(GbaConsole console, int atSecond) {
        GbaAudio audio = console.audio();
        for (boolean channelA : new boolean[] {true, false}) {
            String name = channelA ? "A" : "B";
            long requested = audio.fifoSamplesRequested(channelA);
            long underruns = audio.fifoUnderruns(channelA);
            int timerIndex = audio.directSoundTimer(channelA);
            int periodCycles = console.timers().overflowPeriodCycles(timerIndex);
            double actualHz = requested / (double) DIAGNOSTIC_WINDOW_FRAMES * 60.0;
            double expectedHz = periodCycles == 0 ? 0 : 16_777_216.0 / periodCycles;
            System.out.printf(
                    "t=%3ds DirectSound %s: timer=%d expectedHz=%.1f actualHz=%.1f "
                            + "underrunFraction=%.4f maxOverflowsPerCall=%d%n",
                    atSecond, name, timerIndex, expectedHz, actualHz,
                    requested == 0 ? 0.0 : underruns / (double) requested,
                    audio.maxOverflowsPerCall(channelA));
        }
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
