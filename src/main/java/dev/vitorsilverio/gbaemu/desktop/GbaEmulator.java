package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.armjitter.debug.GdbServer;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveFile;
import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/// Owns the emulation lifecycle for one loaded ROM: the console, its audio output, the
/// cartridge save file, and the dedicated thread that advances the console frame by frame.
///
/// The loop runs under a hard wall-clock frame ceiling (~59.73 Hz, the real GBA refresh).
/// The sound card's blocking write in {@link GbaAudioOutput#pump()} adds fine pacing while
/// audio is playing; the wall-clock cap is what keeps silent scenes (nothing to block on)
/// and audio-buffer refills from outrunning real time. Pausing idles the thread, letting
/// the sound line drain to silence.
public final class GbaEmulator {
    public static final int DEFAULT_CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;
    private static final int GBA_CLOCK_HZ = 1 << 24; // 16.777216 MHz
    // Real GBA refresh: 280896 cycles/frame at 16.78 MHz ~= 59.73 Hz. Used as a hard
    // frame-rate ceiling so light/silent scenes cannot outrun real time.
    private static final long FRAME_NANOS =
            Math.round(1_000_000_000.0 * DEFAULT_CYCLES_PER_FRAME / GBA_CLOCK_HZ);
    // Flush the cartridge save roughly every 10 s of play; closing also flushes once more.
    private static final int AUTOSAVE_INTERVAL_FRAMES = 600;
    private static final long PAUSED_PARK_NANOS = 20_000_000L;

    private final GbaConsole console;
    private final GbaSaveFile saveFile;
    private final GbaAudioOutput audioOutput;
    private final long cyclesPerFrame;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile String error;
    private volatile int gdbPort;
    // Save/load are requested from the UI thread and executed on the emulation thread between
    // frames (so they never race with runCycles). `stateStatus` is the last result for the UI.
    private volatile java.nio.file.Path pendingSaveState;
    private volatile java.nio.file.Path pendingLoadState;
    private volatile String stateStatus;

    /// Enables an embedded GDB remote server on {@code port} (0 = off). When set, the
    /// emulation thread serves a debugger (single-stepping the CPU + hardware) until the
    /// client detaches, then resumes normal execution. Call before {@link #start()}.
    public void enableGdb(int port) {
        this.gdbPort = port;
    }

    public GbaEmulator(GbaConsole console, GbaSaveFile saveFile, AppSettings settings) {
        this.console = console;
        this.saveFile = saveFile;
        this.cyclesPerFrame = DEFAULT_CYCLES_PER_FRAME;
        console.setScanlineRenderingEnabled(settings.scanlineRendering());
        applyAudioSettings(settings);
        this.audioOutput = settings.muteAudio()
                ? GbaAudioOutput.muted(console.audio())
                : GbaAudioOutput.open(console.audio());
    }

    /// Pushes the per-channel mute/volume preferences into the live audio core.
    public void applyAudioSettings(AppSettings settings) {
        for (int channel = 1; channel <= AppSettings.CHANNEL_COUNT; channel++) {
            console.audio().setChannelVolume(channel, settings.channelVolume(channel));
            console.audio().setChannelMuted(channel, settings.isChannelMuted(channel));
        }
    }

    public GbaConsole console() {
        return console;
    }

    public GbaSaveFile saveFile() {
        return saveFile;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    /// The crash message if emulation stopped on an exception, or {@code null}.
    public String error() {
        return error;
    }

    public synchronized void start() {
        if (running.getAndSet(true)) {
            return;
        }
        paused.set(false);
        error = null;
        thread = new Thread(this::loop, "gba-emulation");
        thread.setDaemon(true);
        thread.start();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
        LockSupport.unpark(thread);
    }

    /// Requests a quick-save of the full machine state. Performed on the emulation thread
    /// before the next frame (so it never races with the running CPU); works running or paused.
    public void requestSaveState() {
        pendingSaveState = stateFilePath();
        LockSupport.unpark(thread);
    }

    /// Requests loading the quick-save (no-op with a status message if none exists).
    public void requestLoadState() {
        pendingLoadState = stateFilePath();
        LockSupport.unpark(thread);
    }

    /// Quick-save file: the cartridge save path with a `.ss` extension (one slot per game).
    private java.nio.file.Path stateFilePath() {
        java.nio.file.Path save = saveFile.path();
        String name = save.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        return save.resolveSibling(stem + ".ss");
    }

    /// The last save/load-state outcome message, for the UI to surface (or {@code null}).
    public String stateStatus() {
        return stateStatus;
    }

    private void processStateRequests() {
        java.nio.file.Path save = pendingSaveState;
        if (save != null) {
            pendingSaveState = null;
            try {
                console.saveState(save);
                stateStatus = "Save state gravado: " + save.getFileName();
            } catch (IOException exception) {
                stateStatus = "Falha ao gravar save state: " + exception.getMessage();
            }
            System.out.println(stateStatus);
        }
        java.nio.file.Path load = pendingLoadState;
        if (load != null) {
            pendingLoadState = null;
            if (!java.nio.file.Files.isReadable(load)) {
                stateStatus = "Sem save state em " + load.getFileName();
            } else {
                try {
                    console.loadState(load);
                    audioOutput.silence();
                    stateStatus = "Save state carregado: " + load.getFileName();
                } catch (IOException exception) {
                    stateStatus = "Falha ao carregar save state: " + exception.getMessage();
                }
            }
            System.out.println(stateStatus);
        }
    }

    /// Stops the thread, releases the sound line and flushes the save to disk. Safe to
    /// call more than once.
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            flushSaveQuietly();
            return;
        }
        LockSupport.unpark(thread);
        audioOutput.close(); // unblocks the loop if parked in a blocking write()
        Thread current = thread;
        if (current != null) {
            try {
                current.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        flushSaveQuietly();
    }

    private void loop() {
        if (gdbPort > 0) {
            // Hand the emulation thread to the GDB stub: it single-steps the CPU and ticks
            // all hardware (console.stepCpu(1)) under debugger control until the client
            // detaches, then we fall through to the normal real-time loop below.
            GdbServer.listenAndServe(gdbPort, console.cpu(), console.bus(), () -> console.stepCpu(1));
        }
        long nextFrame = System.nanoTime();
        int framesSinceAutosave = 0;
        while (running.get()) {
            processStateRequests();
            if (paused.get()) {
                LockSupport.parkNanos(PAUSED_PARK_NANOS);
                nextFrame = System.nanoTime();
                continue;
            }
            try {
                console.runCycles(cyclesPerFrame);
                audioOutput.pump();
                if (++framesSinceAutosave >= AUTOSAVE_INTERVAL_FRAMES) {
                    framesSinceAutosave = 0;
                    flushSaveQuietly();
                }
                // Hard real-time ceiling, always applied. Audio's blocking write already
                // paces scenes that produce sound (the card drains during runCycles, so the
                // write blocks for exactly the remaining frame), making this sleep ~0 there.
                // But a silent scene has nothing to block on, so without this the fast JIT
                // runs free ("mega acelerado"); capping here also stops audio-buffer refills
                // from racing at full speed.
                nextFrame += FRAME_NANOS;
                long sleep = nextFrame - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else {
                    nextFrame = System.nanoTime();
                }
            } catch (RuntimeException exception) {
                error = exception.getClass().getSimpleName()
                        + " @ PC=0x" + Integer.toHexString(console.cpu().programCounter());
                System.err.println("Emulation paused: " + error + ": " + exception);
                running.set(false);
            }
        }
    }

    private void flushSaveQuietly() {
        if (saveFile == null) {
            return;
        }
        try {
            saveFile.flush();
        } catch (IOException exception) {
            System.err.println("Falha ao gravar o save em " + saveFile.path() + ": " + exception.getMessage());
        }
    }
}
