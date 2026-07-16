package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.gbaemu.cartridge.GbaCartridgeHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Task D1, teste 4: save state no MEIO de uma transação serial do RTC do cartucho —
/// o estado da máquina de bits (não a hora, que vem sempre do host) precisa sobreviver
/// ao round trip para a transação poder ser concluída corretamente depois de restaurar.
class GbaConsoleRtcSaveStateTest {
    private static final int GPIO_DATA = 0x080000C4;
    private static final int GPIO_DIRECTION = 0x080000C6;
    private static final int GPIO_CONTROL = 0x080000C8;
    private static final int PIN_SCK = 1;
    private static final int PIN_SIO = 2;
    private static final int PIN_CS = 4;

    @Test
    void saveStateMidTransactionAllowsResumingReadOfControlRegister() throws Exception {
        GbaConsole console = GbaConsole.fromRom(emeraldLikeRom());

        console.bus().write16(GPIO_CONTROL, 1);
        console.bus().write16(GPIO_DIRECTION, PIN_SCK | PIN_SIO | PIN_CS);

        // Comeca a transacao e manda so METADE do byte de comando (leitura do
        // registrador de controle: reg=4, leitura -> (1<<7)|(4<<4)|0x6 = 0xC6).
        console.bus().write16(GPIO_DATA, PIN_SCK); // Init SCK=HIGH, CS=LOW
        console.bus().write16(GPIO_DATA, PIN_SCK | PIN_CS); // CS sobe
        sendBits(console, 0xC6, 4); // so os primeiros 4 bits (LSB-first) do comando

        Path state = Files.createTempFile("gbaemu-rtc", ".ss");
        try {
            console.saveState(state);

            // Continua uma sessao NOVA a partir do save state, sem nenhum conhecimento
            // do que ja foi transmitido antes de salvar.
            GbaConsole restored = GbaConsole.fromRom(emeraldLikeRom());
            restored.loadState(state);

            // Termina de enviar os 4 bits restantes do MESMO byte de comando.
            sendRemainingBits(restored, 0xC6, 4);
            // Muda SIO para entrada: agora o chip dirige a linha para a leitura.
            restored.bus().write16(GPIO_DIRECTION, PIN_SCK | PIN_CS);

            int status = readByte(restored);

            assertEquals(0x00, status); // controlRegister default = 00h (Force-Reset)
        } finally {
            Files.deleteIfExists(state);
        }
    }

    private static void sendBits(GbaConsole console, int value, int bitCount) {
        for (int i = 0; i < bitCount; i++) {
            int sio = ((value >>> i) & 1) != 0 ? PIN_SIO : 0;
            console.bus().write16(GPIO_DATA, sio | PIN_CS);
            console.bus().write16(GPIO_DATA, sio | PIN_CS | PIN_SCK);
        }
    }

    private static void sendRemainingBits(GbaConsole console, int value, int fromBit) {
        for (int i = fromBit; i < 8; i++) {
            int sio = ((value >>> i) & 1) != 0 ? PIN_SIO : 0;
            console.bus().write16(GPIO_DATA, sio | PIN_CS);
            console.bus().write16(GPIO_DATA, sio | PIN_CS | PIN_SCK);
        }
    }

    private static int readByte(GbaConsole console) {
        int value = 0;
        for (int i = 0; i < 8; i++) {
            console.bus().write16(GPIO_DATA, PIN_CS);
            int bit = console.bus().read8(GPIO_DATA) & PIN_SIO;
            if (bit != 0) {
                value |= 1 << i;
            }
            console.bus().write16(GPIO_DATA, PIN_CS | PIN_SCK);
        }
        return value;
    }

    private static byte[] emeraldLikeRom() {
        byte[] rom = new byte[0xC0];
        byte[] gameCode = "BPEE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(gameCode, 0, rom, 0xAC, gameCode.length);
        rom[0xB2] = (byte) 0x96;
        rom[0xBD] = (byte) GbaCartridgeHeader.expectedComplement(rom);
        return rom;
    }
}
