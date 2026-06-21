package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.cartridge.GbaSaveFile;
import dev.vitorsilverio.gbaemu.controller.CompositeController;
import dev.vitorsilverio.gbaemu.controller.GamepadController;
import dev.vitorsilverio.gbaemu.controller.KeyboardController;
import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.link.GbaLink;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/// Top-level desktop application: owns the main window, the active emulator and the user
/// settings, and implements every menu action. Lets the user run the emulator entirely
/// from the UI (open a ROM, pause/resume/restart, tweak preferences and open the debug
/// windows) without any command-line arguments.
public final class GbaDesktopApp {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(GbaDesktopApp.class);
    private static final String LAST_ROM_DIRECTORY = "lastRomDirectory";
    private static final String RECENT_ROM_PREFIX = "recentRom";
    private static final int MAX_RECENT_ROMS = 10;

    private AppSettings settings = DesktopAppSettingsStore.load(PREFERENCES);
    private EmulatorWindow window;
    private GbaEmulator activeEmulator;
    private File currentRom;
    private int gdbPort;
    // Input is owned by the app (one keyboard + one gamepad poll thread) and shared across the
    // ROMs the user loads; each emulator just borrows the composite to poll each frame.
    private KeyboardController keyboardController;
    private GamepadController gamepadController;
    private CompositeController controller;
    // The multiplayer link "cable": app-owned (not tied to a console), so it persists across ROM
    // loads/restarts and can be plugged in before a game boots. Every console adopts this one.
    private final GbaLink link = new GbaLink();
    private CpuDebugWindow cpuWindow;
    private PpuDebugWindow ppuWindow;
    private AudioDebugWindow audioWindow;

    private final AudioDebugWindow.ChannelControl audioControl = new AudioDebugWindow.ChannelControl() {
        @Override
        public void setMuted(int channel, boolean muted) {
            settings = settings.withChannelMuted(channel, muted);
            DesktopAppSettingsStore.save(PREFERENCES, settings);
            if (activeEmulator != null) {
                activeEmulator.console().audio().setChannelMuted(channel, muted);
            }
        }

        @Override
        public void setVolume(int channel, int percent) {
            settings = settings.withChannelVolume(channel, percent);
            DesktopAppSettingsStore.save(PREFERENCES, settings);
            if (activeEmulator != null) {
                activeEmulator.console().audio().setChannelVolume(channel, percent);
            }
        }
    };

    /// Opens the main window and, if a ROM was given on the command line, starts it.
    /// {@code gdbPort > 0} starts an embedded GDB server for the loaded ROM (debug).
    public void launch(File initialRom, int gdbPort) {
        this.gdbPort = gdbPort;
        SwingUtilities.invokeLater(() -> {
            keyboardController = new KeyboardController(settings);
            gamepadController = new GamepadController(settings.gamepadConfig());
            controller = new CompositeController(keyboardController, gamepadController);
            window = new EmulatorWindow(menuActions(), settings, keyboardController);
            window.show();
            if (initialRom != null) {
                startEmulator(initialRom);
            }
        });
    }

    private EmulatorMenuActions menuActions() {
        return new EmulatorMenuActions(
                this::openRom,
                this::recentRoms,
                this::openRecentRom,
                this::clearRecentRoms,
                this::pause,
                this::resume,
                this::restart,
                this::stop,
                this::saveState,
                this::loadState,
                this::saveStateToFile,
                this::loadStateFromFile,
                this::linkHost,
                this::linkJoin,
                this::linkDisconnect,
                this::openSettings,
                this::openCpuDebugger,
                this::openPpuDebugger,
                this::openAudioDebugger);
    }

    private void openRom() {
        JFileChooser chooser = new JFileChooser(lastRomDirectory());
        chooser.setDialogTitle("Open ROM");
        chooser.setFileFilter(new FileNameExtensionFilter("GBA ROMs", "gba", "bin", "agb"));
        if (chooser.showOpenDialog(window.owner()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File rom = chooser.getSelectedFile();
        if (rom.getParentFile() != null) {
            PREFERENCES.put(LAST_ROM_DIRECTORY, rom.getParentFile().getAbsolutePath());
        }
        startEmulator(rom);
    }

    private void openRecentRom(File rom) {
        if (rom == null) {
            return;
        }
        if (!rom.isFile()) {
            removeRecentRom(rom);
            JOptionPane.showMessageDialog(window.owner(),
                    "Recent ROM no longer exists:\n" + rom.getAbsolutePath(),
                    "Recent ROMs", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        startEmulator(rom);
    }

    private void startEmulator(File romFile) {
        GbaConsole console;
        GbaSaveFile saveFile;
        try {
            byte[] rom = Files.readAllBytes(romFile.toPath());
            console = createConsole(rom);
            saveFile = new GbaSaveFile(romFile.toPath(), console.backup());
            saveFile.load();
        } catch (IOException | RuntimeException exception) {
            JOptionPane.showMessageDialog(window.owner(),
                    "Failed to load ROM:\n" + romFile.getAbsolutePath() + "\n\n" + exception.getMessage(),
                    "Open ROM", JOptionPane.ERROR_MESSAGE);
            return;
        }
        stopActive();
        currentRom = romFile;
        // Hand the freshly-booted console the app-owned link "cable", so a connection established
        // before/independent of this game is present from its first cycle (and survives restarts).
        console.adoptLink(link);
        activeEmulator = new GbaEmulator(console, saveFile, settings);
        activeEmulator.setController(controller);
        if (gdbPort > 0) {
            activeEmulator.enableGdb(gdbPort);
        }
        window.attach(activeEmulator);
        activeEmulator.start();
        addRecentRom(romFile);
    }

    /// Builds a console honouring the configured boot mode, falling back to a BIOS-less
    /// boot (with a warning) when a BIOS image is required but not available.
    private GbaConsole createConsole(byte[] rom) throws IOException {
        AppSettings.BootMode mode = settings.bootMode();
        boolean useJit = settings.cpuBackend() == AppSettings.CpuBackend.JIT;
        Path biosPath = settings.biosPath().isBlank() ? null : Path.of(settings.biosPath());
        boolean hasBios = biosPath != null && Files.isReadable(biosPath);
        if (mode == AppSettings.BootMode.NO_BIOS || !hasBios) {
            if (mode != AppSettings.BootMode.NO_BIOS) {
                JOptionPane.showMessageDialog(window.owner(),
                        "BIOS file not found for boot mode " + mode.label()
                                + ".\nStarting without a BIOS instead.",
                        "BIOS", JOptionPane.WARNING_MESSAGE);
            }
            return GbaConsole.fromRom(rom, useJit);
        }
        byte[] bios = Files.readAllBytes(biosPath);
        return switch (mode) {
            case HLE -> GbaConsole.fromBiosAndRomHle(bios, rom, useJit);
            case REAL_BIOS -> GbaConsole.fromBiosAndRom(bios, rom, useJit);
            case REAL_SWI -> GbaConsole.fromBiosAndRomRealSwi(bios, rom, useJit);
            case NO_BIOS -> GbaConsole.fromRom(rom, useJit);
        };
    }

    private void pause() {
        if (activeEmulator != null) {
            activeEmulator.pause();
        }
    }

    private void resume() {
        if (activeEmulator != null) {
            activeEmulator.resume();
        }
    }

    private void restart() {
        if (currentRom == null) {
            JOptionPane.showMessageDialog(window.owner(),
                    "Load a ROM before restarting.", "Restart", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // The app-owned link "cable" stays connected across the reboot (the freshly-booted console
        // re-adopts it in startEmulator), so a game can boot from the BIOS into an existing session.
        startEmulator(currentRom);
    }

    private void stop() {
        stopActive();
        window.attach(null);
    }

    private void stopActive() {
        if (activeEmulator != null) {
            activeEmulator.stop();
            activeEmulator = null;
        }
    }

    private void saveState() {
        if (activeEmulator != null) {
            activeEmulator.requestSaveState();
        }
    }

    private void loadState() {
        if (activeEmulator != null) {
            activeEmulator.requestLoadState();
        }
    }

    private void saveStateToFile() {
        Path target = chooseStateFile(true);
        if (target != null && activeEmulator != null) {
            activeEmulator.requestSaveState(target);
        }
    }

    private void loadStateFromFile() {
        Path target = chooseStateFile(false);
        if (target != null && activeEmulator != null) {
            activeEmulator.requestLoadState(target);
        }
    }

    /// Prompts for a `.ss` state file to write or read; returns null if the user cancels or
    /// no ROM is running.
    private Path chooseStateFile(boolean save) {
        if (activeEmulator == null) {
            JOptionPane.showMessageDialog(window.owner(),
                    "Load a ROM before saving or loading a state.", "State", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JFileChooser chooser = new JFileChooser(lastRomDirectory());
        chooser.setDialogTitle(save ? "Save State to File" : "Load State from File");
        chooser.setFileFilter(new FileNameExtensionFilter("gbaemu save states", "ss"));
        int result = save
                ? chooser.showSaveDialog(window.owner())
                : chooser.showOpenDialog(window.owner());
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private void linkHost() {
        String input = JOptionPane.showInputDialog(window.owner(),
                "Port to host the link on:", settings.multiplayerTcpPort());
        if (input == null) {
            return;
        }
        int port = parsePort(input, settings.multiplayerTcpPort());
        settings = settings.withMultiplayer(settings.multiplayerTcpHost(), port, true).normalized();
        DesktopAppSettingsStore.save(PREFERENCES, settings);
        // Operate on the app-owned cable. While a game runs the emulation thread drives the link,
        // so route through it (avoids racing its tick); with no ROM loaded, host directly — the
        // connection then starts pumping as soon as a ROM is loaded and adopts the cable.
        if (activeEmulator != null) {
            activeEmulator.requestHostLink(port);
        } else {
            link.hostTcp("0.0.0.0", port);
        }
        JOptionPane.showMessageDialog(window.owner(),
                "Hosting a link on port " + port + ".\n"
                        + "Have the other instance Join this machine's address (127.0.0.1 if local).\n"
                        + "You can host/join before loading a ROM — the cable stays plugged as games boot.",
                "Link", JOptionPane.INFORMATION_MESSAGE);
    }

    private void linkJoin() {
        String input = JOptionPane.showInputDialog(window.owner(),
                "Host to join (host:port):",
                settings.multiplayerTcpHost() + ":" + settings.multiplayerTcpPort());
        if (input == null || input.isBlank()) {
            return;
        }
        String host = settings.multiplayerTcpHost();
        int port = settings.multiplayerTcpPort();
        String trimmed = input.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0) {
            host = trimmed.substring(0, colon).trim();
            port = parsePort(trimmed.substring(colon + 1), port);
        } else {
            host = trimmed;
        }
        settings = settings.withMultiplayer(host, port, false).normalized();
        DesktopAppSettingsStore.save(PREFERENCES, settings);
        if (activeEmulator != null) {
            activeEmulator.requestJoinLink(host, port);
        } else {
            link.joinTcp(host, port);
        }
        JOptionPane.showMessageDialog(window.owner(),
                "Joining " + host + ":" + port + ".\n"
                        + "The cable stays connected while games boot. For link games like Mario Kart,\n"
                        + "the extra players must boot into the session: connect first, then load/Restart\n"
                        + "the ROM so it boots from the BIOS already on the link.",
                "Link", JOptionPane.INFORMATION_MESSAGE);
    }

    private void linkDisconnect() {
        if (activeEmulator != null) {
            activeEmulator.requestDisconnectLink();
        } else {
            link.disconnect();
        }
    }

    private static int parsePort(String text, int fallback) {
        try {
            return Math.max(1, Math.min(65535, Integer.parseInt(text.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void openSettings() {
        new SettingsDialog(window.owner(), settings, this::applySettings).setVisible(true);
    }

    private void applySettings(AppSettings updated) {
        settings = updated.normalized();
        DesktopAppSettingsStore.save(PREFERENCES, settings);
        window.applySettings(settings);
        keyboardController.applySettings(settings);
        gamepadController.applySettings(settings.gamepadConfig());
        if (activeEmulator != null) {
            activeEmulator.applyAudioSettings(settings);
            activeEmulator.console().setScanlineRenderingEnabled(settings.scanlineRendering());
        }
    }

    private GbaConsole activeConsole() {
        return activeEmulator == null ? null : activeEmulator.console();
    }

    private void openCpuDebugger() {
        if (cpuWindow == null) {
            cpuWindow = new CpuDebugWindow(this::activeConsole);
        } else {
            cpuWindow.present();
        }
    }

    private void openPpuDebugger() {
        if (ppuWindow == null) {
            ppuWindow = new PpuDebugWindow(this::activeConsole);
        } else {
            ppuWindow.present();
        }
    }

    private void openAudioDebugger() {
        if (audioWindow == null) {
            audioWindow = new AudioDebugWindow(this::activeConsole, settings, audioControl);
        } else {
            audioWindow.present();
        }
    }

    private File lastRomDirectory() {
        String configured = PREFERENCES.get(LAST_ROM_DIRECTORY, "");
        if (!configured.isBlank()) {
            File directory = new File(configured);
            if (directory.isDirectory()) {
                return directory;
            }
        }
        return new File(System.getProperty("user.dir"));
    }

    private List<File> recentRoms() {
        List<File> files = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_ROMS; i++) {
            String path = PREFERENCES.get(RECENT_ROM_PREFIX + i, "");
            if (path.isBlank()) {
                continue;
            }
            File file = new File(path);
            if (file.isFile() && !files.contains(file)) {
                files.add(file);
            }
        }
        return files;
    }

    private void addRecentRom(File romFile) {
        File normalized = romFile.getAbsoluteFile();
        List<File> files = new ArrayList<>();
        files.add(normalized);
        for (File recent : recentRoms()) {
            if (!recent.getAbsoluteFile().equals(normalized) && files.size() < MAX_RECENT_ROMS) {
                files.add(recent);
            }
        }
        saveRecentRoms(files);
    }

    private void removeRecentRom(File romFile) {
        File normalized = romFile.getAbsoluteFile();
        List<File> files = new ArrayList<>();
        for (File recent : recentRoms()) {
            if (!recent.getAbsoluteFile().equals(normalized)) {
                files.add(recent);
            }
        }
        saveRecentRoms(files);
    }

    private void clearRecentRoms() {
        saveRecentRoms(List.of());
    }

    private void saveRecentRoms(List<File> files) {
        for (int i = 0; i < MAX_RECENT_ROMS; i++) {
            if (i < files.size()) {
                PREFERENCES.put(RECENT_ROM_PREFIX + i, files.get(i).getAbsolutePath());
            } else {
                PREFERENCES.remove(RECENT_ROM_PREFIX + i);
            }
        }
    }
}
