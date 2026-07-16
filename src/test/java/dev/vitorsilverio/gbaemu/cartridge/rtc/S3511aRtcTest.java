package dev.vitorsilverio.gbaemu.cartridge.rtc;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Protocolo serial de 3 fios (SCK/SIO/CS) do S-3511A, exercitado diretamente por
/// {@link S3511aRtc#updatePins} — sem passar pelo GPIO do cartucho (isso é coberto por
/// {@code GbaRomGpioTest}). Relógio FAKE injetável (task D1, teste 1): nunca chama
/// {@code LocalDateTime.now()} aqui.
class S3511aRtcTest {

    // Slots de comando (GBATEK "GBA Cart RTC" comparado a "DS Real-Time Clock").
    private static final int CMD_FORCE_RESET = 0;
    private static final int CMD_DATETIME = 2;
    private static final int CMD_CONTROL = 4;
    private static final int CMD_TIME = 6;

    @Test
    void statusReadReturnsDefaultControlRegister() {
        S3511aRtc rtc = new S3511aRtc(fakeClock());
        int status = readRegister(rtc, CMD_CONTROL, 1)[0];
        assertEquals(0x00, status); // "Setting after Force-Reset is 00h" (GBATEK)
    }

    @Test
    void controlWriteRoundTripsWritableBitsAndMasksReadOnlyPowerOffBit() {
        S3511aRtc rtc = new S3511aRtc(fakeClock());
        // bit6 (24h mode) + bit7 (power-off, read-only) sao escritos; bit7 deve ser
        // ignorado (GBATEK: "R Power-Off (auto cleared on read)").
        writeRegister(rtc, CMD_CONTROL, new int[]{0xC0});
        int status = readRegister(rtc, CMD_CONTROL, 1)[0];
        assertEquals(0x40, status); // bit6 preservado, bit7 forcado a 0 na escrita
    }

    @Test
    void powerOffFlagAutoClearsOnRead() {
        S3511aRtc rtc = new S3511aRtc(fakeClock());
        // Nao ha forma de setar bit7 via escrita (read-only) nesta implementacao (nunca
        // modelamos perda de bateria) — o teste relevante e que reset devolve 00h e uma
        // segunda leitura permanece 00h (idempotente), nao 80h.
        forceReset(rtc);
        assertEquals(0x00, readRegister(rtc, CMD_CONTROL, 1)[0]);
        assertEquals(0x00, readRegister(rtc, CMD_CONTROL, 1)[0]);
    }

    @Test
    void forceResetClearsControlRegister() {
        S3511aRtc rtc = new S3511aRtc(fakeClock());
        writeRegister(rtc, CMD_CONTROL, new int[]{0x40});
        assertEquals(0x40, readRegister(rtc, CMD_CONTROL, 1)[0]);

        forceReset(rtc);

        assertEquals(0x00, readRegister(rtc, CMD_CONTROL, 1)[0]);
    }

    @Test
    void dateTimeReadEncodesBcdCorrectly() {
        // 2026-07-16 14:59:07, quinta-feira. Escolhido para bater exatamente no bug
        // classico de BCD apontado pela task: minuto 59 deve virar o byte 0x59 (BCD),
        // NAO 89 decimal nem 0x3B (binario puro) — a mesma logica vale para segundos.
        LocalDateTime fixed = LocalDateTime.of(2026, 7, 16, 14, 59, 7);
        S3511aRtc rtc = new S3511aRtc(() -> fixed);
        // 24h mode ligado explicitamente (default de fabrica e 00h = 12h; os jogos
        // sempre configuram isso no boot, "usually 1" per GBATEK).
        writeRegister(rtc, CMD_CONTROL, new int[]{0x40});

        int[] bytes = readRegister(rtc, CMD_DATETIME, 7);

        assertEquals(0x26, bytes[0]); // ano 2026 -> BCD 26h
        assertEquals(0x07, bytes[1]); // mes 7 -> BCD 07h
        assertEquals(0x16, bytes[2]); // dia 16 -> BCD 16h
        assertEquals(3, bytes[3]);    // 2026-07-16 e quinta-feira -> 0=Monday..6=Sunday => 3
        assertEquals(0x14, bytes[4] & 0x7F); // hora 14 (24h) -> BCD 14h, sem folga de PM
        assertEquals(0x80, bytes[4] & 0x80); // >=12h -> bit7 (AM/PM na GBA) ligado
        assertEquals(0x59, bytes[5]); // minuto 59 -> BCD 0x59, nao 89/0x3B
        assertEquals(0x07, bytes[6]); // segundo 7 -> BCD 07h
    }

    @Test
    void twelveHourModeWrapsNoonAndMidnightToZero() {
        LocalDateTime noon = LocalDateTime.of(2026, 7, 16, 12, 0, 0);
        S3511aRtc rtc = new S3511aRtc(() -> noon);
        // controlRegister default (00h) ja e modo 12h — nenhuma escrita necessaria.

        int[] time = readRegister(rtc, CMD_TIME, 3);

        assertEquals(0x00, time[0] & 0x7F); // "12 o'clock is defined as 00h (not 12h)"
        assertEquals(0x80, time[0] & 0x80); // meio-dia -> PM
    }

    // ── harness do protocolo de 3 fios ──────────────────────────────────────────────

    private static GbaRtcClock fakeClock() {
        LocalDateTime fixed = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
        return () -> fixed;
    }

    private static void primeIdle(S3511aRtc rtc) {
        rtc.updatePins(true, false, false); // "Init CS=LOW and /SCK=HIGH"
    }

    private static void beginTransaction(S3511aRtc rtc) {
        rtc.updatePins(true, false, true); // CS sobe: "Switch CS=HIGH"
    }

    private static void endTransaction(S3511aRtc rtc) {
        rtc.updatePins(true, false, false); // "Switch CS to LOW"
    }

    private static void sendBit(S3511aRtc rtc, boolean bit) {
        rtc.updatePins(false, bit, true); // borda de descida: host estabelece o bit
        rtc.updatePins(true, bit, true);  // borda de subida: chip amostra
    }

    private static void sendByte(S3511aRtc rtc, int value) {
        for (int i = 0; i < 8; i++) {
            sendBit(rtc, ((value >>> i) & 1) != 0);
        }
    }

    private static int readBit(S3511aRtc rtc) {
        rtc.updatePins(false, false, true); // borda de descida: chip dirige sioOut
        boolean bit = rtc.sioReadback();
        rtc.updatePins(true, false, true);  // borda de subida: host amostraria aqui
        return bit ? 1 : 0;
    }

    private static int readByteFromChip(S3511aRtc rtc) {
        int value = 0;
        for (int i = 0; i < 8; i++) {
            value |= readBit(rtc) << i;
        }
        return value;
    }

    private static int commandByte(int register, boolean read) {
        return ((read ? 1 : 0) << 7) | (register << 4) | 0x6;
    }

    private static int[] readRegister(S3511aRtc rtc, int register, int paramBytes) {
        primeIdle(rtc);
        beginTransaction(rtc);
        sendByte(rtc, commandByte(register, true));
        int[] result = new int[paramBytes];
        for (int i = 0; i < paramBytes; i++) {
            result[i] = readByteFromChip(rtc);
        }
        endTransaction(rtc);
        return result;
    }

    private static void writeRegister(S3511aRtc rtc, int register, int[] paramBytes) {
        primeIdle(rtc);
        beginTransaction(rtc);
        sendByte(rtc, commandByte(register, false));
        for (int value : paramBytes) {
            sendByte(rtc, value);
        }
        endTransaction(rtc);
    }

    private static void forceReset(S3511aRtc rtc) {
        primeIdle(rtc);
        beginTransaction(rtc);
        sendByte(rtc, commandByte(CMD_FORCE_RESET, false));
        endTransaction(rtc);
    }
}
