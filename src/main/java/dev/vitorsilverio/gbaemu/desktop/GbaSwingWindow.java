package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.input.GbaButton;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/// Janela Swing simples para visualizar o framebuffer enquanto a emulacao roda.
///
/// A emulacao roda numa thread dedicada, ritmada pela placa de som (o write
/// bloqueante de {@link GbaAudioOutput#pump()} e o relogio de tempo real). A
/// renderizacao do video acontece no EDT, numa cadencia propria e desacoplada, para
/// nunca roubar tempo do caminho critico do audio. A thread de emulacao le/escreve o
/// estado do console enquanto o EDT le a VRAM para desenhar; eventuais "tearing" sao
/// aceitaveis aqui e muito menos perceptiveis que falhas de audio.
public final class GbaSwingWindow {
    public static final int DEFAULT_CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;
    private static final long FRAME_NANOS = 1_000_000_000L / 60;

    private GbaSwingWindow() {
    }

    public static void open(GbaConsole console, int scale, int stepsPerFrame) throws InterruptedException {
        open(console, scale, stepsPerFrame, DEFAULT_CYCLES_PER_FRAME, false, false);
    }

    public static void open(GbaConsole console, int scale, int stepsPerFrame, boolean debugVideo) throws InterruptedException {
        open(console, scale, stepsPerFrame, DEFAULT_CYCLES_PER_FRAME, debugVideo, false);
    }

    public static void open(
            GbaConsole console,
            int scale,
            int stepsPerFrame,
            int cyclesPerFrame,
            boolean debugVideo,
            boolean muteAudio) throws InterruptedException {
        if (stepsPerFrame < 0) {
            throw new IllegalArgumentException("stepsPerFrame must be >= 0");
        }
        if (cyclesPerFrame < 0) {
            throw new IllegalArgumentException("cyclesPerFrame must be >= 0");
        }
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(false);

        SwingUtilities.invokeLater(() -> {
            GbaFramePanel panel = new GbaFramePanel(scale);
            GbaAudioOutput audioOutput = muteAudio ? GbaAudioOutput.muted(console.audio()) : GbaAudioOutput.open(console.audio());
            JFrame frame = new JFrame("gbaemu");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    setButton(console, event, true);
                }

                @Override
                public void keyReleased(KeyEvent event) {
                    setButton(console, event, false);
                }
            });

            boolean audioIsClock = audioOutput.isActive();
            Thread emulationThread = new Thread(
                    () -> runEmulation(console, audioOutput, audioIsClock, stepsPerFrame, cyclesPerFrame, running, frame),
                    "gba-emulation");
            emulationThread.setDaemon(true);

            // Video pump on the EDT: decoupled from emulation, so a slow render frame
            // never starves the sound card.
            final int[] renderedFrames = {0};
            Timer renderTimer = new Timer(16, event -> {
                try {
                    int[] renderedFrame = console.renderFrame();
                    panel.setFrame(renderedFrame);
                    frame.setTitle(statusTitle(console, running.get() ? "running" : "paused"));
                    if (debugVideo && renderedFrames[0]++ % 60 == 0) {
                        System.out.println("video: " + console.videoFrameStats(renderedFrame).compactSummary());
                    }
                } catch (RuntimeException exception) {
                    // A torn read during rendering is harmless; skip this frame.
                }
            });

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    running.set(false);
                    renderTimer.stop();
                    audioOutput.close(); // unblocks the emulation thread if parked in write()
                    try {
                        emulationThread.join(1000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    closed.countDown();
                }
            });

            int[] renderedFrame = console.renderFrame();
            panel.setFrame(renderedFrame);
            frame.setTitle(statusTitle(console, "ready"));
            if (debugVideo) {
                System.out.println("video: " + console.videoFrameStats(renderedFrame).compactSummary());
            }

            running.set(true);
            emulationThread.start();
            renderTimer.start();
            frame.setVisible(true);
        });
        if (!SwingUtilities.isEventDispatchThread()) {
            closed.await();
        }
    }

    private static void runEmulation(
            GbaConsole console,
            GbaAudioOutput audioOutput,
            boolean audioIsClock,
            int stepsPerFrame,
            int cyclesPerFrame,
            AtomicBoolean running,
            JFrame frame) {
        long nextFrame = System.nanoTime();
        while (running.get()) {
            try {
                if (stepsPerFrame > 0) {
                    console.stepCpu(stepsPerFrame);
                } else if (cyclesPerFrame > 0) {
                    console.runCycles(cyclesPerFrame);
                }
                audioOutput.pump();
                if (!audioIsClock) {
                    // No sound line to pace us: fall back to a wall-clock frame pacer.
                    nextFrame += FRAME_NANOS;
                    long sleep = nextFrame - System.nanoTime();
                    if (sleep > 0) {
                        LockSupport.parkNanos(sleep);
                    } else {
                        nextFrame = System.nanoTime();
                    }
                }
            } catch (RuntimeException exception) {
                running.set(false);
                String pc = Integer.toHexString(console.cpu().programCounter());
                System.err.println("Emulation paused at PC=0x" + pc + ": " + exception);
                SwingUtilities.invokeLater(() -> frame.setTitle(
                        "gbaemu - paused: " + exception.getClass().getSimpleName() + " - PC=0x" + pc));
            }
        }
    }

    private static String statusTitle(GbaConsole console, String state) {
        return "gbaemu - " + state
                + " - PC=0x" + Integer.toHexString(console.cpu().programCounter())
                + " cycles=" + console.cpu().cycles()
                + " VCOUNT=" + console.lcdTiming().scanline();
    }

    private static void setButton(GbaConsole console, KeyEvent event, boolean pressed) {
        GbaButton button = switch (event.getKeyCode()) {
            case KeyEvent.VK_X -> GbaButton.A;
            case KeyEvent.VK_Z -> GbaButton.B;
            case KeyEvent.VK_ENTER -> GbaButton.START;
            case KeyEvent.VK_SHIFT -> GbaButton.SELECT;
            case KeyEvent.VK_RIGHT -> GbaButton.RIGHT;
            case KeyEvent.VK_LEFT -> GbaButton.LEFT;
            case KeyEvent.VK_UP -> GbaButton.UP;
            case KeyEvent.VK_DOWN -> GbaButton.DOWN;
            case KeyEvent.VK_A -> GbaButton.L;
            case KeyEvent.VK_S -> GbaButton.R;
            default -> null;
        };
        if (button != null) {
            console.keypad().setPressed(button, pressed);
            event.consume();
        }
    }
}
