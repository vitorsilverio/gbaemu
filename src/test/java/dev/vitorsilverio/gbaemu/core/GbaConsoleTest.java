package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GbaConsoleTest {
    @Test
    void fromRomBootstrapsCpuAtTheCartridgeEntryPoint() {
        GbaConsole console = GbaConsole.fromRom(minimalRom());

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(0, console.memory().read32(GbaConsole.ROM_ENTRY_POINT));
        assertNotNull(console.runtime());
        assertNotNull(console.video());
        assertNotNull(console.lcdTiming());
        assertNotNull(console.dma());
        assertNotNull(console.interrupts());
        assertNotNull(console.timers());
        assertNotNull(console.keypad());
        assertNotNull(console.cartridge());
    }

    @Test
    void fromBiosAndRomBootstrapsCpuAtTheBiosVector() {
        byte[] bios = new byte[0x4000];
        bios[0] = 0x11;
        bios[1] = 0x22;
        bios[2] = 0x33;
        bios[3] = 0x44;

        GbaConsole console = GbaConsole.fromBiosAndRom(bios, minimalRom());

        assertEquals(GbaConsole.BIOS_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(0x44332211, console.memory().read32(GbaConsole.BIOS_ENTRY_POINT));
    }

    private static byte[] minimalRom() {
        byte[] rom = new byte[0xC0];
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) dev.vitorsilverio.gbaemu.cartridge.GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }
}
