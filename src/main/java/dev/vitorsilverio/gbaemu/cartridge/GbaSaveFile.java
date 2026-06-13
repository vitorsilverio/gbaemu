package dev.vitorsilverio.gbaemu.cartridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// Persiste a memoria de save do cartucho (SRAM/Flash) num arquivo `.sav` ao lado da
/// ROM, no mesmo formato cru que emuladores como mGBA/VBA usam (o conteudo do chip,
/// sem cabecalho). Carrega na inicializacao e grava apenas quando o conteudo muda.
public final class GbaSaveFile {
    private final Path path;
    private final CartridgeBackup backup;
    private byte[] lastWritten;

    public GbaSaveFile(Path romPath, CartridgeBackup backup) {
        this.path = deriveSavePath(romPath);
        this.backup = backup;
    }

    public Path path() {
        return path;
    }

    /// True when the cartridge actually has a save chip worth persisting.
    public boolean isPersistable() {
        return backup.isPersistable();
    }

    /// Loads the `.sav` into the save memory if it exists. Safe to call once before the
    /// emulation thread starts (no concurrent access yet).
    public boolean load() throws IOException {
        if (!isPersistable() || !Files.exists(path)) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(path);
        backup.load(bytes);
        lastWritten = backup.snapshot();
        return true;
    }

    /// Writes the current save memory to disk if it changed since the last write.
    /// Returns true when a write actually happened. Call from the emulation thread (or
    /// after it has stopped) so the snapshot is internally consistent.
    public boolean flush() throws IOException {
        if (!isPersistable()) {
            return false;
        }
        byte[] snapshot = backup.snapshot();
        if (Arrays.equals(snapshot, lastWritten)) {
            return false;
        }
        Files.write(path, snapshot);
        lastWritten = snapshot;
        return true;
    }

    private static Path deriveSavePath(Path romPath) {
        Path absolute = romPath.toAbsolutePath();
        String fileName = absolute.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        Path parent = absolute.getParent();
        Path saveName = Path.of(stem + ".sav");
        return parent == null ? saveName : parent.resolve(saveName);
    }
}
