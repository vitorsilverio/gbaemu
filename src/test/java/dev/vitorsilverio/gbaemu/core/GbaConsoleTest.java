package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GbaConsoleTest {
    @Test
    void fromRomBootstrapsCpuAtTheCartridgeEntryPoint() {
        GbaConsole console = GbaConsole.fromRom(minimalRom());

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(GbaConsole.USER_STACK_POINTER, console.cpu().register(13));
        assertEquals(1, console.memory().read8(0x04000300));
        assertEquals(0, console.memory().read32(GbaConsole.ROM_ENTRY_POINT));
        assertNotNull(console.runtime());
        assertNotNull(console.video());
        assertNotNull(console.lcdTiming());
        assertNotNull(console.dma());
        assertNotNull(console.interrupts());
        assertNotNull(console.timers());
        assertNotNull(console.keypad());
        assertNotNull(console.cartridge());
        assertNotNull(console.systemControl());
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
        assertEquals(GbaConsole.SUPERVISOR_STACK_POINTER, console.cpu().register(13));
        assertEquals(0, console.memory().read8(0x04000300));
        assertEquals(0x44332211, console.memory().read32(GbaConsole.BIOS_ENTRY_POINT));
    }

    @Test
    void fromBiosAndRomHleKeepsBiosMappedAndStartsAtTheCartridgeEntryPoint() {
        byte[] bios = new byte[0x4000];
        bios[0] = 0x11;
        bios[1] = 0x22;
        bios[2] = 0x33;
        bios[3] = 0x44;

        GbaConsole console = GbaConsole.fromBiosAndRomHle(bios, minimalRom());

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(GbaConsole.USER_STACK_POINTER, console.cpu().register(13));
        assertEquals(1, console.memory().read8(0x04000300));
        assertEquals(0x44332211, console.memory().read32(GbaConsole.BIOS_ENTRY_POINT));
    }

    @Test
    void applySkipBiosStateCanBeAppliedAfterCreatingBiosConsole() {
        byte[] bios = new byte[0x4000];
        GbaConsole console = GbaConsole.fromBiosAndRom(bios, minimalRom());

        console.applySkipBiosState();

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(GbaConsole.USER_STACK_POINTER, console.cpu().register(13));
        assertEquals(1, console.memory().read8(0x04000300));
    }

    private static byte[] minimalRom() {
        byte[] rom = new byte[0xC0];
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) dev.vitorsilverio.gbaemu.cartridge.GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }
}
