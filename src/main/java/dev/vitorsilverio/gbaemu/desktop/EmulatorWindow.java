package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.input.GbaButton;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;

/// The main emulator window: a scaled framebuffer plus a menu bar. It is a thin view —
/// all behaviour is delegated to {@link EmulatorMenuActions}, and it renders whatever
/// {@link GbaEmulator} is currently attached. Video is pumped on a Swing timer, fully
/// decoupled from the emulation thread so a slow repaint never starves the sound card.
public final class EmulatorWindow {
    private static final int RENDER_INTERVAL_MS = 16;

    private final JFrame frame = new JFrame("gbaemu");
    private final EmulatorMenuActions actions;
    private GbaFramePanel panel;
    private int scale;
    private volatile GbaEmulator emulator;

    private final Timer renderTimer = new Timer(RENDER_INTERVAL_MS, event -> renderTick());

    public EmulatorWindow(EmulatorMenuActions actions, AppSettings settings) {
        this.actions = actions;
        this.scale = settings.scale();
        this.panel = new GbaFramePanel(scale);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        panel.setBackground(Color.BLACK);
        installKeyListener();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                renderTimer.stop();
                actions.stop().run();
                frame.dispose();
                System.exit(0);
            }
        });
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            renderTimer.start();
        });
    }

    public Frame owner() {
        return frame;
    }

    /// Points the window at a newly started emulator; the render loop and key input
    /// follow whatever is attached here.
    public void attach(GbaEmulator emulator) {
        this.emulator = emulator;
    }

    public void applySettings(AppSettings settings) {
        setScale(settings.scale());
    }

    private void setScale(int newScale) {
        if (newScale == scale) {
            return;
        }
        this.scale = newScale;
        SwingUtilities.invokeLater(() -> {
            panel = new GbaFramePanel(scale);
            panel.setBackground(Color.BLACK);
            frame.setContentPane(panel);
            frame.pack();
        });
    }

    private void renderTick() {
        GbaEmulator active = emulator;
        if (active == null) {
            frame.setTitle("gbaemu");
            return;
        }
        try {
            panel.setFrame(active.console().currentFrame());
        } catch (RuntimeException ignored) {
            // A torn read mid-render is harmless; just skip this frame.
        }
        frame.setTitle(statusTitle(active));
    }

    private static String statusTitle(GbaEmulator active) {
        if (active.error() != null) {
            return "gbaemu - crashed: " + active.error();
        }
        GbaConsole console = active.console();
        String state = active.isPaused() ? "paused" : (active.isRunning() ? "running" : "stopped");
        return "gbaemu - " + state
                + " - PC=0x" + Integer.toHexString(console.cpu().programCounter())
                + " VCOUNT=" + console.lcdTiming().scanline();
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(buildEmulatorMenu());
        menuBar.add(buildDebugMenu());
        return menuBar;
    }

    private JMenu buildEmulatorMenu() {
        JMenu menu = new JMenu("Emulator");

        JMenuItem openRom = item("Open ROM...", KeyEvent.VK_O, actions.openRom());
        menu.add(openRom);
        menu.add(buildRecentRomsMenu());
        menu.addSeparator();
        menu.add(item("Pause", KeyEvent.VK_P, actions.pause()));
        menu.add(item("Resume", KeyEvent.VK_R, actions.resume()));
        JMenuItem restart = new JMenuItem("Restart");
        restart.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0));
        restart.addActionListener(event -> actions.restart().run());
        menu.add(restart);
        menu.add(plainItem("Stop", actions.stop()));
        menu.addSeparator();
        menu.add(item("Settings...", KeyEvent.VK_COMMA, actions.openSettings()));
        menu.addSeparator();
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        menu.add(exit);
        return menu;
    }

    private JMenu buildRecentRomsMenu() {
        JMenu recent = new JMenu("Recent ROMs");
        recent.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent event) {
                rebuildRecentRoms(recent);
            }

            @Override
            public void menuDeselected(MenuEvent event) {
            }

            @Override
            public void menuCanceled(MenuEvent event) {
            }
        });
        rebuildRecentRoms(recent);
        return recent;
    }

    private void rebuildRecentRoms(JMenu recent) {
        recent.removeAll();
        List<File> roms = actions.recentRoms().get();
        if (roms.isEmpty()) {
            JMenuItem empty = new JMenuItem("(empty)");
            empty.setEnabled(false);
            recent.add(empty);
            return;
        }
        for (File rom : roms) {
            JMenuItem item = new JMenuItem(rom.getName());
            item.setToolTipText(rom.getAbsolutePath());
            item.addActionListener(event -> actions.openRecentRom().accept(rom));
            recent.add(item);
        }
        recent.addSeparator();
        JMenuItem clear = new JMenuItem("Clear recent ROMs");
        clear.addActionListener(event -> {
            actions.clearRecentRoms().run();
            rebuildRecentRoms(recent);
        });
        recent.add(clear);
    }

    private JMenu buildDebugMenu() {
        JMenu menu = new JMenu("Debug");
        menu.add(plainItem("CPU registers...", actions.cpuDebugger()));
        menu.add(plainItem("PPU / video...", actions.ppuDebugger()));
        menu.add(plainItem("Audio channels...", actions.audioDebugger()));
        return menu;
    }

    private JMenuItem item(String label, int keyCode, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(KeyStroke.getKeyStroke(keyCode, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        item.addActionListener(event -> action.run());
        return item;
    }

    private JMenuItem plainItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> action.run());
        return item;
    }

    /// Routes key presses to the keypad through the global focus manager, so input works
    /// regardless of which child component (panel, menu bar) holds focus. It only acts
    /// when this window is the active window and the focus is not in a text field, so the
    /// Settings dialog and debug-window tables keep normal keyboard behaviour.
    private void installKeyListener() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            int id = event.getID();
            if (id != KeyEvent.KEY_PRESSED && id != KeyEvent.KEY_RELEASED) {
                return false;
            }
            if (event.getComponent() instanceof JTextComponent) {
                return false;
            }
            Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (active != frame) {
                return false;
            }
            if (id == KeyEvent.KEY_PRESSED && handleStateHotkey(event.getKeyCode())) {
                return false;
            }
            setButton(event, id == KeyEvent.KEY_PRESSED);
            return false;
        });
    }

    /// F5 = quick save state, F8 = quick load state. Returns true if the key was handled.
    private boolean handleStateHotkey(int keyCode) {
        GbaEmulator active = emulator;
        if (active == null) {
            return false;
        }
        if (keyCode == KeyEvent.VK_F5) {
            active.requestSaveState();
            return true;
        }
        if (keyCode == KeyEvent.VK_F8) {
            active.requestLoadState();
            return true;
        }
        return false;
    }

    private void setButton(KeyEvent event, boolean pressed) {
        GbaEmulator active = emulator;
        if (active == null) {
            return;
        }
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
            active.console().keypad().setPressed(button, pressed);
            event.consume();
        }
    }
}
