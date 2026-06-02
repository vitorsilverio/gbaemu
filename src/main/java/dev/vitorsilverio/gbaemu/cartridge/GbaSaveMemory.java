package dev.vitorsilverio.gbaemu.cartridge;

import java.util.Arrays;

/// Memoria persistente inicial de cartucho.
public final class GbaSaveMemory {
    public static final int SRAM_SIZE = 64 * 1024;
    public static final int FLASH_1M_SIZE = 128 * 1024;
    private static final int FLASH_BANK_SIZE = 64 * 1024;
    private static final int FLASH_UNLOCK_1 = 0x5555;
    private static final int FLASH_UNLOCK_2 = 0x2AAA;
    private static final int FLASH_SECTOR_SIZE = 4 * 1024;
    private static final int FLASH_MANUFACTURER_MACRONIX = 0xC2;
    private static final int FLASH_DEVICE_MACRONIX_64K = 0x1C;
    private static final int FLASH_DEVICE_MACRONIX_128K = 0x09;

    private final GbaSaveType type;
    private final byte[] data;
    private int flashUnlockStep;
    private boolean flashIdMode;
    private boolean flashEraseArmed;
    private FlashWriteMode flashWriteMode = FlashWriteMode.READY;
    private int flashBank;

    private GbaSaveMemory(GbaSaveType type, int size) {
        this.type = type;
        this.data = new byte[size];
        Arrays.fill(data, (byte) 0xFF);
    }

    public static GbaSaveMemory forType(GbaSaveType type) {
        return switch (type) {
            case NONE -> new GbaSaveMemory(type, 0);
            case FLASH_1M -> new GbaSaveMemory(type, FLASH_1M_SIZE);
            case SRAM, FLASH, FLASH_512, EEPROM -> new GbaSaveMemory(type, SRAM_SIZE);
        };
    }

    public GbaSaveType type() {
        return type;
    }

    public int size() {
        return data.length;
    }

    public int read8(int address) {
        if (data.length == 0) {
            return 0xFF;
        }
        if (isFlash()) {
            int offset = address & 0xFFFF;
            if (flashIdMode) {
                return flashIdRead(offset);
            }
            return data[flashIndex(offset)] & 0xFF;
        }
        return data[Math.floorMod(address, data.length)] & 0xFF;
    }

    public void write8(int address, int value) {
        if (data.length == 0) {
            return;
        }
        int data8 = value & 0xFF;
        if (isFlash()) {
            writeFlash(address & 0xFFFF, data8);
            return;
        }
        data[Math.floorMod(address, data.length)] = (byte) data8;
    }

    public byte[] snapshot() {
        return Arrays.copyOf(data, data.length);
    }

    public void load(byte[] snapshot) {
        Arrays.fill(data, (byte) 0xFF);
        System.arraycopy(snapshot, 0, data, 0, Math.min(snapshot.length, data.length));
    }

    private boolean isFlash() {
        return type == GbaSaveType.FLASH || type == GbaSaveType.FLASH_512 || type == GbaSaveType.FLASH_1M;
    }

    private int flashIdRead(int offset) {
        return switch (offset & 0xFFFF) {
            case 0 -> FLASH_MANUFACTURER_MACRONIX;
            case 1 -> type == GbaSaveType.FLASH_1M ? FLASH_DEVICE_MACRONIX_128K : FLASH_DEVICE_MACRONIX_64K;
            default -> 0xFF;
        };
    }

    private void writeFlash(int offset, int value) {
        if (value == 0xF0) {
            resetFlashCommandState();
            flashIdMode = false;
            return;
        }

        switch (flashWriteMode) {
            case PROGRAM -> {
                int index = flashIndex(offset);
                data[index] = (byte) ((data[index] & 0xFF) & value);
                resetFlashCommandState();
                return;
            }
            case BANK_SELECT -> {
                flashBank = type == GbaSaveType.FLASH_1M ? value & 1 : 0;
                resetFlashCommandState();
                return;
            }
            case READY -> {
            }
        }

        if (flashUnlockStep == 0) {
            if (offset == FLASH_UNLOCK_1 && value == 0xAA) {
                flashUnlockStep = 1;
            }
            return;
        }
        if (flashUnlockStep == 1) {
            flashUnlockStep = offset == FLASH_UNLOCK_2 && value == 0x55 ? 2 : 0;
            return;
        }

        handleUnlockedFlashCommand(offset, value);
    }

    private void handleUnlockedFlashCommand(int offset, int value) {
        flashUnlockStep = 0;
        if (flashEraseArmed) {
            if (offset == FLASH_UNLOCK_1 && value == 0x10) {
                Arrays.fill(data, (byte) 0xFF);
            } else if (value == 0x30) {
                eraseFlashSector(offset);
            }
            flashEraseArmed = false;
            return;
        }

        if (offset != FLASH_UNLOCK_1) {
            return;
        }
        switch (value) {
            case 0x80 -> flashEraseArmed = true;
            case 0x90 -> flashIdMode = true;
            case 0xA0 -> flashWriteMode = FlashWriteMode.PROGRAM;
            case 0xB0 -> flashWriteMode = FlashWriteMode.BANK_SELECT;
            case 0xF0 -> flashIdMode = false;
            default -> {
            }
        }
    }

    private void eraseFlashSector(int offset) {
        int start = flashIndex(offset) & ~(FLASH_SECTOR_SIZE - 1);
        int end = Math.min(start + FLASH_SECTOR_SIZE, data.length);
        Arrays.fill(data, start, end, (byte) 0xFF);
    }

    private int flashIndex(int offset) {
        int bankBase = type == GbaSaveType.FLASH_1M ? flashBank * FLASH_BANK_SIZE : 0;
        return Math.floorMod(bankBase + (offset & 0xFFFF), data.length);
    }

    private void resetFlashCommandState() {
        flashUnlockStep = 0;
        flashEraseArmed = false;
        flashWriteMode = FlashWriteMode.READY;
    }

    private enum FlashWriteMode {
        READY,
        PROGRAM,
        BANK_SELECT
    }
}
