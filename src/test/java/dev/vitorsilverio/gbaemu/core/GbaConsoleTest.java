package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GbaConsoleTest {
    @Test
    void saveStateRoundTripsCpuRamAndIoRegisters() throws Exception {
        GbaConsole console = GbaConsole.fromRom(minimalRom());
        console.cpu().setRegister(5, 0xCAFEBABE);
        console.bus().write32(0x02000100, 0x11112222); // EWRAM
        console.bus().write32(0x03000200, 0x33334444); // IWRAM
        console.bus().write16(0x05000010, 0x5AA5);      // palette
        console.bus().write32(0x06000300, 0x55556666); // VRAM
        console.bus().write16(0x07000020, 0x0BAD);      // OAM
        console.bus().write16(0x04000200, 0x3FFF);      // REG_IE (an I/O peripheral)

        Path state = Files.createTempFile("gbaemu", ".ss");
        try {
            console.saveState(state);

            console.cpu().setRegister(5, 0);
            console.bus().write32(0x02000100, 0);
            console.bus().write32(0x03000200, 0);
            console.bus().write16(0x05000010, 0);
            console.bus().write32(0x06000300, 0);
            console.bus().write16(0x07000020, 0);
            console.bus().write16(0x04000200, 0);

            console.loadState(state);

            assertEquals(0xCAFEBABE, console.cpu().register(5));
            assertEquals(0x11112222, console.bus().read32(0x02000100));
            assertEquals(0x33334444, console.bus().read32(0x03000200));
            assertEquals(0x5AA5, console.bus().read16(0x05000010));
            assertEquals(0x55556666, console.bus().read32(0x06000300));
            assertEquals(0x0BAD, console.bus().read16(0x07000020));
            assertEquals(0x3FFF, console.bus().read16(0x04000200));
        } finally {
            Files.deleteIfExists(state);
        }
    }

    @Test
    void fromRomBootstrapsCpuAtTheCartridgeEntryPoint() {
        GbaConsole console = GbaConsole.fromRom(minimalRom());

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(GbaConsole.USER_STACK_POINTER, console.cpu().register(13));
        assertEquals(1, console.bus().read8(0x04000300));
        assertEquals(0, console.bus().read32(GbaConsole.ROM_ENTRY_POINT));
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
        assertEquals(0, console.bus().read8(0x04000300));
        assertEquals(0x44332211, console.bus().read32(GbaConsole.BIOS_ENTRY_POINT));
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
        assertEquals(1, console.bus().read8(0x04000300));
        assertEquals(0x44332211, console.bus().read32(GbaConsole.BIOS_ENTRY_POINT));
    }

    @Test
    void applySkipBiosStateCanBeAppliedAfterCreatingBiosConsole() {
        byte[] bios = new byte[0x4000];
        GbaConsole console = GbaConsole.fromBiosAndRom(bios, minimalRom());

        console.applySkipBiosState();

        assertEquals(GbaConsole.ROM_ENTRY_POINT, console.cpu().programCounter());
        assertEquals(GbaConsole.USER_STACK_POINTER, console.cpu().register(13));
        assertEquals(1, console.bus().read8(0x04000300));
    }

    private static byte[] minimalRom() {
        byte[] rom = new byte[0xC0];
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) dev.vitorsilverio.gbaemu.cartridge.GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }
}
