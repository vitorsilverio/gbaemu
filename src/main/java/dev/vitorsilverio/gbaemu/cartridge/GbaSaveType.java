package dev.vitorsilverio.gbaemu.cartridge;

/// Tipos comuns de save detectados por assinatura ASCII na ROM.
public enum GbaSaveType {
    NONE,
    SRAM,
    FLASH,
    FLASH_512,
    FLASH_1M,
    EEPROM
}
