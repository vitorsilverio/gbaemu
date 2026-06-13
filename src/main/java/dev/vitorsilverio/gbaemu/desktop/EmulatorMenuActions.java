package dev.vitorsilverio.gbaemu.desktop;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// The set of callbacks the {@link EmulatorWindow} menu bar invokes. Kept as a plain
/// record of functional values so the window owns no orchestration logic and the wiring
/// (ROM loading, lifecycle, debug windows) lives in {@link GbaDesktopApp}.
public record EmulatorMenuActions(
        Runnable openRom,
        Supplier<List<File>> recentRoms,
        Consumer<File> openRecentRom,
        Runnable clearRecentRoms,
        Runnable pause,
        Runnable resume,
        Runnable restart,
        Runnable stop,
        Runnable openSettings,
        Runnable cpuDebugger,
        Runnable ppuDebugger,
        Runnable audioDebugger) {
}
