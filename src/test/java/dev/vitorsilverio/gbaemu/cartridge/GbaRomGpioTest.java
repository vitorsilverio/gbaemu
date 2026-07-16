package dev.vitorsilverio.gbaemu.cartridge;

import dev.vitorsilverio.gbaemu.cartridge.rtc.S3511aRtc;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Janela de GPIO (0x080000C4-C9) de {@link GbaRom} — GBATEK "GBA Cart I/O Port
/// (GPIO)". O protocolo do chip S-3511A em si é testado isoladamente em
/// {@code S3511aRtcTest}; aqui o foco é a fiação Data/Direction/Control ↔ endereços.
class GbaRomGpioTest {
    private static final int GPIO_DATA = 0x080000C4;
    private static final int GPIO_DIRECTION = 0x080000C6;
    private static final int GPIO_CONTROL = 0x080000C8;

    private static final int PIN_SCK = 0;
    private static final int PIN_SIO = 1;
    private static final int PIN_CS = 2;

    @Test
    void cartridgeWithoutRtcReadsPlainRomBytes() {
        // Regressao (G3): os 5 jogos de referencia (sem RTC) nao devem notar nenhuma
        // mudanca nesta janela de enderecos — continua sendo ROM zero-preenchida.
        GbaRom rom = new GbaRom(new byte[0x200]);

        rom.writeHalfWord(GPIO_CONTROL, 1); // liga leitura, se houvesse RTC
        rom.writeHalfWord(GPIO_DATA, 0xF);

        assertEquals(0, rom.readByte(GPIO_DATA));
        assertEquals(0, rom.readByte(GPIO_CONTROL));
    }

    @Test
    void readsReturnRomDataWhileControlEnableBitIsClear() {
        // GBATEK: "In write-only mode, reads return 00h" — o teste 2 da task D1.
        GbaRom rom = new GbaRom(new byte[0x200], new S3511aRtc(() -> LocalDateTime.of(2026, 1, 1, 0, 0, 0)));

        rom.writeHalfWord(GPIO_DIRECTION, allOut());
        rom.writeHalfWord(GPIO_DATA, 0xF); // dado real escrito no latch...
        // ...mas controle continua 0 (write-only): leitura deve ignorar o latch.

        assertEquals(0, rom.readByte(GPIO_DATA));

        rom.writeHalfWord(GPIO_CONTROL, 1); // agora habilita leitura
        // So os bits 0-2 (SCK/SIO/CS) estao ligados ao RTC (allOut = saida so para
        // eles); bit3 nao tem nenhum dispositivo nesta implementacao e le 0 mesmo com
        // 1111b escrito no latch — por isso o esperado e 0x7, nao 0xF.
        assertEquals(allOut(), rom.readByte(GPIO_DATA));
    }

    @Test
    void outputPinReadbackReturnsLastWrittenLatch() {
        GbaRom rom = new GbaRom(new byte[0x200], new S3511aRtc(() -> LocalDateTime.of(2026, 1, 1, 0, 0, 0)));
        rom.writeHalfWord(GPIO_CONTROL, 1);
        rom.writeHalfWord(GPIO_DIRECTION, allOut());

        rom.writeHalfWord(GPIO_DATA, bit(PIN_SCK) | bit(PIN_CS));

        assertEquals(bit(PIN_SCK) | bit(PIN_CS), rom.readByte(GPIO_DATA));
        assertEquals(allOut(), rom.readByte(GPIO_DIRECTION));
    }

    @Test
    void sioInputPinReadsBackWhatTheChipDrives() {
        S3511aRtc rtc = new S3511aRtc(() -> LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        GbaRom rom = new GbaRom(new byte[0x200], rtc);
        rom.writeHalfWord(GPIO_CONTROL, 1);
        // SCK e CS sao sempre saida do host; SIO comeca como saida (escrita de comando).
        rom.writeHalfWord(GPIO_DIRECTION, bit(PIN_SCK) | bit(PIN_SIO) | bit(PIN_CS));

        // "Init CS=LOW and /SCK=HIGH"
        rom.writeHalfWord(GPIO_DATA, bit(PIN_SCK));
        // CS sobe: inicia a transacao (comando = Force Reset: reg=0, escrita, 0x06).
        rom.writeHalfWord(GPIO_DATA, bit(PIN_SCK) | bit(PIN_CS));
        sendByte(rom, 0x06, bit(PIN_CS));
        // CS desce: fim da transacao.
        rom.writeHalfWord(GPIO_DATA, bit(PIN_SCK));

        // Agora um comando de leitura de status (reg=4): resultado deve ser 00h (reset
        // acima ja zerou o registrador de controle) — lido de volta via o pino SIO como
        // ENTRADA, exercitando o caminho `readGpioDataByte`/`rtc.sioReadback()`.
        rom.writeHalfWord(GPIO_DATA, bit(PIN_SCK) | bit(PIN_CS));
        sendByte(rom, 0xC6, bit(PIN_CS)); // (1<<7)|(4<<4)|0x6 = 0xC6, leitura de controle
        // muda SIO para entrada: agora quem dirige a linha e o chip.
        rom.writeHalfWord(GPIO_DIRECTION, bit(PIN_SCK) | bit(PIN_CS));

        int status = readByte(rom, bit(PIN_CS));

        assertEquals(0x00, status);
    }

    private static int allOut() {
        return bit(PIN_SCK) | bit(PIN_SIO) | bit(PIN_CS);
    }

    private static int bit(int pin) {
        return 1 << pin;
    }

    private static void sendByte(GbaRom rom, int value, int csBit) {
        for (int i = 0; i < 8; i++) {
            boolean sioBit = ((value >>> i) & 1) != 0;
            int sio = sioBit ? bit(PIN_SIO) : 0;
            rom.writeHalfWord(GPIO_DATA, sio | csBit);           // SCK cai (setup)
            rom.writeHalfWord(GPIO_DATA, sio | csBit | bit(PIN_SCK)); // SCK sobe (amostra)
        }
    }

    private static int readByte(GbaRom rom, int csBit) {
        int value = 0;
        for (int i = 0; i < 8; i++) {
            rom.writeHalfWord(GPIO_DATA, csBit); // SCK cai: chip dirige o proximo bit
            int bit = rom.readByte(GPIO_DATA) & bit(PIN_SIO);
            if (bit != 0) {
                value |= 1 << i;
            }
            rom.writeHalfWord(GPIO_DATA, csBit | bit(PIN_SCK)); // SCK sobe
        }
        return value;
    }
}
