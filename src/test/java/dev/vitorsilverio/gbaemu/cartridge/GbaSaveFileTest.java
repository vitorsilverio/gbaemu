package dev.vitorsilverio.gbaemu.cartridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaSaveFileTest {

    @Test
    void savePathSitsBesideTheRomWithSavExtension(@TempDir Path dir) {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);
        GbaSaveFile saveFile = new GbaSaveFile(dir.resolve("pokefirered.gba"), save);

        assertEquals(dir.resolve("pokefirered.sav").toAbsolutePath(), saveFile.path());
    }

    @Test
    void flushWritesTheChipContentsAndLoadRestoresThem(@TempDir Path dir) throws Exception {
        GbaSaveMemory original = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);
        original.write8(0x0042, 0x12);
        original.write8(0x1FFFF, 0x34);
        GbaSaveFile writer = new GbaSaveFile(dir.resolve("game.gba"), original);

        assertTrue(writer.flush());
        assertEquals(GbaSaveMemory.FLASH_1M_SIZE, Files.size(writer.path()));

        GbaSaveMemory restored = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);
        GbaSaveFile reader = new GbaSaveFile(dir.resolve("game.gba"), restored);

        assertTrue(reader.load());
        assertArrayEquals(original.snapshot(), restored.snapshot());
    }

    @Test
    void flushIsANoOpWhenNothingChanged(@TempDir Path dir) throws Exception {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.SRAM);
        save.write8(0, 0x5A);
        GbaSaveFile saveFile = new GbaSaveFile(dir.resolve("game.gba"), save);

        assertTrue(saveFile.flush());
        assertFalse(saveFile.flush(), "second flush without changes should not rewrite the file");

        save.write8(0, 0x00); // flash/SRAM write -> content changed
        assertTrue(saveFile.flush(), "a changed save should be written again");
    }

    @Test
    void cartridgesWithoutSaveAreNotPersisted(@TempDir Path dir) throws Exception {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.NONE);
        GbaSaveFile saveFile = new GbaSaveFile(dir.resolve("game.gba"), save);

        assertFalse(saveFile.isPersistable());
        assertFalse(saveFile.flush());
        assertFalse(saveFile.load());
        assertFalse(Files.exists(saveFile.path()));
    }

    @Test
    void loadReturnsFalseWhenNoSaveFileExistsYet(@TempDir Path dir) throws Exception {
        GbaSaveMemory save = GbaSaveMemory.forType(GbaSaveType.FLASH_1M);
        GbaSaveFile saveFile = new GbaSaveFile(dir.resolve("fresh.gba"), save);

        assertFalse(saveFile.load());
    }
}
