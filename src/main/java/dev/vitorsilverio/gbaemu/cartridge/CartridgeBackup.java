package dev.vitorsilverio.gbaemu.cartridge;

/// A cartridge-backed save store that can be persisted to a `.sav` file. Implemented by the
/// memory-mapped SRAM/Flash store ({@link GbaSaveMemory}) and the serial EEPROM
/// ({@link GbaEepromSave}), so {@link GbaSaveFile} can persist either uniformly.
public interface CartridgeBackup {
    /// A copy of the chip's current contents, in the raw `.sav` byte layout.
    byte[] snapshot();

    /// Replaces the chip's contents from a previously taken snapshot (extra bytes ignored,
    /// missing bytes left at the power-on value).
    void load(byte[] data);

    /// True when there is a real save chip whose contents are worth writing to disk.
    boolean isPersistable();
}
