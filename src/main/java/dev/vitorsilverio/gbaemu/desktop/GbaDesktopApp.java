package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.cartridge.GbaSaveFile;
import dev.vitorsilverio.gbaemu.core.GbaConsole;

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
            window = new EmulatorWindow(menuActions(), settings);
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
        activeEmulator = new GbaEmulator(console, saveFile, settings);
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
        Path biosPath = settings.biosPath().isBlank() ? null : Path.of(settings.biosPath());
        boolean hasBios = biosPath != null && Files.isReadable(biosPath);
        if (mode == AppSettings.BootMode.NO_BIOS || !hasBios) {
            if (mode != AppSettings.BootMode.NO_BIOS) {
                JOptionPane.showMessageDialog(window.owner(),
                        "BIOS file not found for boot mode " + mode.label()
                                + ".\nStarting without a BIOS instead.",
                        "BIOS", JOptionPane.WARNING_MESSAGE);
            }
            return GbaConsole.fromRom(rom);
        }
        byte[] bios = Files.readAllBytes(biosPath);
        return switch (mode) {
            case HLE -> GbaConsole.fromBiosAndRomHle(bios, rom);
            case REAL_BIOS -> GbaConsole.fromBiosAndRom(bios, rom);
            case REAL_SWI -> GbaConsole.fromBiosAndRomRealSwi(bios, rom);
            case NO_BIOS -> GbaConsole.fromRom(rom);
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

    private void openSettings() {
        new SettingsDialog(window.owner(), settings, this::applySettings).setVisible(true);
    }

    private void applySettings(AppSettings updated) {
        settings = updated.normalized();
        DesktopAppSettingsStore.save(PREFERENCES, settings);
        window.applySettings(settings);
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
