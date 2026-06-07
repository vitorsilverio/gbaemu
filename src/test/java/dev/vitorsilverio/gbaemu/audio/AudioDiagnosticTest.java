package dev.vitorsilverio.gbaemu.audio;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Opt-in real-ROM audio checks (the commercial ROM is not in the repo). Run with
/// -Daudio.diag=1; skipped otherwise (and skipped if the ROM/BIOS are absent).
///
/// They run pokefirered through the same per-frame loop the Swing window uses
/// (runCycles(oneFrame) + drainPcm), assert the mix is healthy, and write WAVs
/// (target/audio-diag.wav, target/audio-bios.wav) so the output can be verified by ear.
class AudioDiagnosticTest {
    private static final int SAMPLE_RATE = 32768;
    private static final int SAMPLES_PER_PUMP = SAMPLE_RATE / 60;
    private static final int CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void pokefireredAudioIsHealthy() throws Exception {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));

        int totalFrames = 900; // 15 emulated seconds, well past the title music start
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long railed = 0;
        long clicks = 0;
        long total = 0;
        int min = 127;
        int max = -128;
        byte prev = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            console.runCycles(CYCLES_PER_FRAME);
            for (byte b : console.audio().drainPcm(SAMPLES_PER_PUMP)) {
                if (b == 127 || b == -128) railed++;
                if (Math.abs(b - prev) > 100) clicks++; // abrupt jump = audible click
                if (b < min) min = b;
                if (b > max) max = b;
                prev = b;
                total++;
                int v = b << 8;
                pcm.write(v & 0xFF);
                pcm.write((v >>> 8) & 0xFF);
            }
        }

        writeWav(Path.of("target", "audio-diag.wav"), pcm.toByteArray());

        assertTrue(total > 0, "no audio produced");
        double railedFraction = (double) railed / total;
        // The PSG DC-offset bug railed the mix to a constant +127 (~0.7 of samples).
        assertTrue(railedFraction < 0.10,
                "audio is railed/clipped " + (railedFraction * 100) + "% of the time (PSG DC offset regression?)");
        // The sound-DMA source-reload bug made the FIFO play IWRAM garbage: ~0.5% of
        // samples were >100-jump clicks. A healthy stream is well under that.
        double clickFraction = (double) clicks / total;
        assertTrue(clickFraction < 0.002,
                "audio has " + (clickFraction * 100) + "% click jumps (sound-DMA reload regression?)");
        assertTrue(max - min > 64, "audio has no dynamic range (min=" + min + " max=" + max + ")");
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void biosIntroAudioIsNotRailed() throws Exception {
        Path biosPath = Path.of("gba_bios.bin");
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(biosPath) && Files.exists(romPath), "gba_bios.bin / ROM not present");
        GbaConsole console = GbaConsole.fromBiosAndRom(Files.readAllBytes(biosPath), Files.readAllBytes(romPath));

        int totalFrames = 240; // ~4s: covers the boot animation + chime
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long railed = 0;
        long nonZero = 0;
        long total = 0;
        for (int frame = 0; frame < totalFrames; frame++) {
            console.runCycles(CYCLES_PER_FRAME);
            for (byte b : console.audio().drainPcm(SAMPLES_PER_PUMP)) {
                if (b == 127 || b == -128) railed++;
                if (b != 0) nonZero++;
                total++;
                int v = b << 8;
                pcm.write(v & 0xFF);
                pcm.write((v >>> 8) & 0xFF);
            }
        }

        writeWav(Path.of("target", "audio-bios.wav"), pcm.toByteArray());

        // The chime may not play if the boot sequence stalls (separate known issue);
        // only assert the mix quality when audio was actually produced.
        if (nonZero > total / 100) {
            double railedFraction = (double) railed / total;
            assertTrue(railedFraction < 0.10,
                    "BIOS audio is railed/clipped " + (railedFraction * 100) + "% of the time");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "audio.diag", matches = "1")
    void measurePerFrameCost() throws Exception {
        Path romPath = Path.of("pokefirered.gba");
        assumeTrue(Files.exists(romPath), "pokefirered.gba not present");
        GbaConsole console = GbaConsole.fromRom(Files.readAllBytes(romPath));

        for (int i = 0; i < 120; i++) { // warm up the JIT
            console.runCycles(CYCLES_PER_FRAME);
            console.renderFrame();
        }

        int frames = 300;
        long emulateNs = 0;
        long renderNs = 0;
        long statsNs = 0;
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            console.runCycles(CYCLES_PER_FRAME);
            long t1 = System.nanoTime();
            int[] f = console.renderFrame();
            long t2 = System.nanoTime();
            console.videoFrameStats(f);
            long t3 = System.nanoTime();
            emulateNs += t1 - t0;
            renderNs += t2 - t1;
            statsNs += t3 - t2;
        }
        double e = emulateNs / 1e6 / frames;
        double r = renderNs / 1e6 / frames;
        double s = statsNs / 1e6 / frames;
        System.out.printf(
                "PER-FRAME ms: emulate=%.2f render=%.2f stats=%.2f total=%.2f (budget 16.67ms -> %.2fx realtime)%n",
                e, r, s, e + r + s, 16.667 / (e + r + s));
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
