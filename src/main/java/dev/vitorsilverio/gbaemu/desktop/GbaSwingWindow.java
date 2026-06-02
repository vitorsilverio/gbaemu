package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.input.GbaButton;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import dev.vitorsilverio.gbaemu.video.GbaVideoFrameStats;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;

/// Janela Swing simples para visualizar o framebuffer enquanto a emulacao roda.
public final class GbaSwingWindow {
    public static final int DEFAULT_CYCLES_PER_FRAME =
            GbaLcdTiming.CYCLES_PER_SCANLINE * GbaLcdTiming.TOTAL_SCANLINES;

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

            Timer timer = new Timer(16, null);
            final int[] renderedFrames = {0};
            timer.addActionListener(event -> {
                try {
                    if (stepsPerFrame > 0) {
                        console.stepCpu(stepsPerFrame);
                    } else if (cyclesPerFrame > 0) {
                        console.runCycles(cyclesPerFrame);
                    }
                    int[] renderedFrame = console.renderFrame();
                    GbaVideoFrameStats stats = console.videoFrameStats(renderedFrame);
                    panel.setFrame(renderedFrame);
                    audioOutput.pump();
                    frame.setTitle(statusTitle(console, "running", stats));
                    if (debugVideo && renderedFrames[0]++ % 60 == 0) {
                        System.out.println("video: " + stats.compactSummary());
                    }
                } catch (RuntimeException exception) {
                    timer.stop();
                    frame.setTitle(statusTitle(console, "paused: " + exception.getClass().getSimpleName(), null));
                    System.err.println("Emulation paused at PC=0x"
                            + Integer.toHexString(console.cpu().programCounter())
                            + ": " + exception);
                }
            });
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    timer.stop();
                    audioOutput.close();
                    closed.countDown();
                }
            });
            int[] renderedFrame = console.renderFrame();
            GbaVideoFrameStats stats = console.videoFrameStats(renderedFrame);
            panel.setFrame(renderedFrame);
            frame.setTitle(statusTitle(console, "ready", stats));
            if (debugVideo) {
                System.out.println("video: " + stats.compactSummary());
            }
            timer.start();
            frame.setVisible(true);
        });
        if (!SwingUtilities.isEventDispatchThread()) {
            closed.await();
        }
    }

    private static String statusTitle(GbaConsole console, String state, GbaVideoFrameStats stats) {
        String title = "gbaemu - " + state
                + " - PC=0x" + Integer.toHexString(console.cpu().programCounter())
                + " cycles=" + console.cpu().cycles()
                + " VCOUNT=" + console.lcdTiming().scanline();
        if (stats != null) {
            title += " mode=" + stats.mode()
                    + " DISPCNT=0x" + String.format("%04X", stats.dispcnt())
                    + " colors=" + stats.uniqueColors()
                    + " nonBackdrop=" + stats.nonBackdropPixels();
        }
        return title;
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
