package dev.vitorsilverio.gbaemu.cartridge.rtc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;

/// Chip RTC Seiko S-3511A (Pokémon Ruby/Sapphire/Emerald, Boktai) — protocolo serial de
/// 3 fios (SCK/SIO/CS) descrito em GBATEK "GBA Cart Real-Time Clock (RTC)" + "DS
/// Real-Time Clock (RTC)" (o chip da GBA reaproveita o protocolo do NDS quase por
/// completo; only a tabela de comandos e o bit AM/PM do registrador de hora mudam).
///
/// Esta classe só conhece os 3 fios (como o chip real) — não sabe nada sobre o
/// registrador de Direção do GPIO do cartucho; quem decide se o pino SIO está sendo
/// dirigido pelo host ou pelo chip é [dev.vitorsilverio.gbaemu.cartridge.GbaRom].
///
/// v1 (task D1): data/hora sempre lida AO VIVO do [GbaRtcClock] no instante em que o
/// comando é decodificado — não há campos internos de ano/mês/dia/hora presistidos;
/// escritas em Data&Time/Time são aceitas (protocolo completo, byte a byte) mas não têm
/// efeito observável, documentado explicitamente (sem offset ainda). O único registrador
/// realmente stateful é o de controle (modo 12/24h + flag de power-off).
public final class S3511aRtc {

    // ── protocolo: layout do byte de comando (GBATEK "DS Real-Time Clock", transmissão
    // LSB-first — a convenção que a imensa maioria dos jogos usa) ──────────────────────
    // bit0-3 = código fixo (0110b), bits4-6 = número do comando, bit7 = direção (1=leitura)
    private static final int COMMAND_BITS = 8;
    private static final int COMMAND_FIELD_SHIFT = 4;
    private static final int COMMAND_FIELD_MASK = 0x7;
    private static final int READ_DIRECTION_BIT = 7;

    // ── mapa de comandos GBA (GBATEK "GBA Cart RTC", tabela "Comparision of RTC
    // Registers" — o GBA reaproveita os slots do NDS mas troca o significado de alguns) ─
    private static final int CMD_FORCE_RESET = 0; // 0 bytes, strobed (era stat1/NDS)
    private static final int CMD_ALARM1_UNUSED = 1; // 1 byte, sempre FFh (era int1/NDS)
    private static final int CMD_DATETIME = 2; // 7 bytes: ano,mes,dia,dow,hh,mm,ss
    private static final int CMD_FORCE_IRQ = 3; // 0 bytes, strobed (era clock-adjust/NDS)
    private static final int CMD_CONTROL = 4; // 1 byte (era stat2/NDS)
    private static final int CMD_ALARM2_UNUSED = 5; // 3 bytes, sempre FFh
    private static final int CMD_TIME = 6; // 3 bytes: hh,mm,ss
    private static final int CMD_FREE_UNUSED = 7; // 1 byte, sempre FFh

    // ── registrador de controle (GBATEK "GBA Cart RTC", "Control Register") ────────────
    private static final int CONTROL_24H_MODE_BIT = 0x40; // 0=12h, 1=24h (usually 1)
    private static final int CONTROL_POWER_OFF_BIT = 0x80; // R, auto-cleared on read
    // Bits graváveis pelo jogo (1,3,5,6); os demais (0,2,4,7) são sempre zerados na
    // escrita — bit7 é read-only (auto-clear), os outros são "not used" no GBATEK.
    private static final int CONTROL_WRITABLE_MASK = 0b0110_1010;
    // "Setting after Force-Reset is 00h" (GBATEK) — usado tanto no boot quanto no
    // comando de Force Reset; garante que o jogo nunca veja o power-off flag ligado
    // sem que uma perda de energia real tenha sido modelada (não é o caso aqui).
    private static final int CONTROL_RESET_VALUE = 0x00;

    private static final int BITS_PER_BYTE = 8;
    private static final int MAX_PARAM_BYTES = 7; // date&time é o maior (7 bytes)

    private enum Phase { IDLE, COMMAND, PARAM }

    private final GbaRtcClock clock;

    private boolean prevSck;
    private boolean prevCs;
    private Phase phase = Phase.IDLE;
    private int bitCount;
    private int shiftValue;
    private int command;
    private boolean readDirection;
    private int paramTotalBytes;
    private int paramIndex;
    private final byte[] paramBuffer = new byte[MAX_PARAM_BYTES];
    private boolean sioOut;
    private int controlRegister = CONTROL_RESET_VALUE;

    public S3511aRtc(GbaRtcClock clock) {
        this.clock = clock;
    }

    /// Bit que o chip está dirigindo na linha SIO — relevante só quando o cartucho
    /// configurou o pino SIO do GPIO como entrada (ou seja, quem dirige a linha é o RTC).
    public boolean sioReadback() {
        return sioOut;
    }

    /// Chamado a cada escrita no registrador de Dados do GPIO (offset 0x080000C4), com o
    /// nível atual dos 3 fios ligados ao RTC (ver GBATEK "Connection Examples": SCK=bit0,
    /// SIO=bit1, CS=bit2). Detecta bordas de CS (início/fim de transação) e de SCK
    /// (transferência de 1 bit, LSB-first, "data is output on/immediately after falling
    /// edge" — ver GBATEK "DS Real-Time Clock", "Bit transfer").
    public void updatePins(boolean sck, boolean sio, boolean cs) {
        if (cs && !prevCs) {
            beginTransaction();
        } else if (!cs && prevCs) {
            phase = Phase.IDLE; // CS caiu: transação incompleta é abandonada sem commit
        } else if (cs && !prevSck && sck) {
            onClockRisingEdge(sio);
        } else if (cs && prevSck && !sck) {
            onClockFallingEdge();
        }
        prevSck = sck;
        prevCs = cs;
    }

    private void beginTransaction() {
        phase = Phase.COMMAND;
        bitCount = 0;
        shiftValue = 0;
    }

    /// Borda de subida: o lado que ESCREVE (sempre o host no byte de comando; o host de
    /// novo nos bytes de parâmetro quando a direção é escrita) já estabilizou o bit no SIO
    /// na borda de descida anterior — este é o instante em que o outro lado amostra.
    private void onClockRisingEdge(boolean sio) {
        switch (phase) {
            case COMMAND -> {
                shiftValue |= (sio ? 1 : 0) << bitCount;
                bitCount++;
                if (bitCount == COMMAND_BITS) {
                    decodeCommand();
                }
            }
            case PARAM -> {
                if (!readDirection) {
                    shiftValue |= (sio ? 1 : 0) << bitCount;
                }
                bitCount++;
                if (bitCount == BITS_PER_BYTE) {
                    if (!readDirection) {
                        paramBuffer[paramIndex] = (byte) shiftValue;
                    }
                    advanceParamByte();
                }
            }
            case IDLE -> { /* bits fora de uma transação são ignorados */ }
        }
    }

    /// Borda de descida: se o CHIP é quem escreve (fase de parâmetro em leitura), ele
    /// dirige agora o próximo bit do byte corrente para o host amostrar na próxima subida.
    private void onClockFallingEdge() {
        if (phase == Phase.PARAM && readDirection) {
            sioOut = ((paramBuffer[paramIndex] >>> bitCount) & 1) != 0;
        }
    }

    private void advanceParamByte() {
        bitCount = 0;
        shiftValue = 0;
        paramIndex++;
        if (paramIndex == paramTotalBytes) {
            if (!readDirection) {
                commitWrite();
            }
            phase = Phase.IDLE;
        }
    }

    private void decodeCommand() {
        command = (shiftValue >>> COMMAND_FIELD_SHIFT) & COMMAND_FIELD_MASK;
        readDirection = ((shiftValue >>> READ_DIRECTION_BIT) & 1) != 0;
        bitCount = 0;
        shiftValue = 0;
        paramIndex = 0;
        paramTotalBytes = paramBytesFor(command);

        if (paramTotalBytes == 0) {
            strobe(command);
            phase = Phase.IDLE;
            return;
        }
        phase = Phase.PARAM;
        if (readDirection) {
            fillParamBufferForRead(command);
        }
    }

    private static int paramBytesFor(int command) {
        return switch (command) {
            case CMD_FORCE_RESET, CMD_FORCE_IRQ -> 0;
            case CMD_ALARM1_UNUSED, CMD_CONTROL, CMD_FREE_UNUSED -> 1;
            case CMD_ALARM2_UNUSED, CMD_TIME -> 3;
            case CMD_DATETIME -> 7;
            default -> 0;
        };
    }

    /// Registradores "strobed by ANY access" (GBATEK): a ação acontece assim que o byte de
    /// comando é recebido, sem parâmetros.
    private void strobe(int command) {
        if (command == CMD_FORCE_RESET) {
            controlRegister = CONTROL_RESET_VALUE;
        }
        // CMD_FORCE_IRQ: pulso de /IRQ não modelado (sem efeito observável; fora do
        // escopo da task D1, documentado no arquivo da task).
    }

    private void fillParamBufferForRead(int command) {
        switch (command) {
            case CMD_DATETIME -> encodeDateTime(clock.now());
            case CMD_TIME -> encodeTime(clock.now());
            case CMD_CONTROL -> {
                paramBuffer[0] = (byte) controlRegister;
                controlRegister &= ~CONTROL_POWER_OFF_BIT; // auto-cleared on read
            }
            default -> Arrays.fill(paramBuffer, 0, paramTotalBytes, (byte) 0xFF); // alarm/free unused
        }
    }

    private void commitWrite() {
        if (command == CMD_CONTROL) {
            controlRegister = paramBuffer[0] & CONTROL_WRITABLE_MASK;
        }
        // Date&Time/Time: aceitos por completo (framing do protocolo) mas sem efeito
        // persistido nesta v1 — a hora vem sempre ao vivo do host (ver GbaRtcClock).
        // Alarm/free: sempre FFh, escritas ignoradas.
    }

    private void encodeDateTime(LocalDateTime now) {
        paramBuffer[0] = bcd(now.getYear() % 100);
        paramBuffer[1] = bcd(now.getMonthValue());
        paramBuffer[2] = bcd(now.getDayOfMonth());
        paramBuffer[3] = (byte) (now.getDayOfWeek().getValue() - 1); // 0=Monday..6=Sunday
        paramBuffer[4] = encodeHour(now.getHour());
        paramBuffer[5] = bcd(now.getMinute());
        paramBuffer[6] = bcd(now.getSecond());
    }

    private void encodeTime(LocalDateTime now) {
        paramBuffer[0] = encodeHour(now.getHour());
        paramBuffer[1] = bcd(now.getMinute());
        paramBuffer[2] = bcd(now.getSecond());
    }

    /// Bit AM/PM na GBA fica no bit7 do byte de hora (GBATEK: "AM/PM flag moved from
    /// hour.bit6 (NDS) to hour.bit7 (GBA)"); em modo 12h, meio-dia/meia-noite é 00h, não
    /// 12h.
    private byte encodeHour(int hour24) {
        boolean is24h = (controlRegister & CONTROL_24H_MODE_BIT) != 0;
        int hourField = is24h ? hour24 : hour24 % 12;
        int value = bcd(hourField) & 0x7F;
        if (hour24 >= 12) {
            value |= 0x80;
        }
        return (byte) value;
    }

    private static byte bcd(int value) {
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    /// Serializa o estado da máquina serial (comando corrente, bit-shift, direção dos
    /// pinos) — a hora NÃO é salva (vem do host; ver classe javadoc). Task D1, item 3.
    public void saveState(DataOutputStream out) throws IOException {
        out.writeBoolean(prevSck);
        out.writeBoolean(prevCs);
        out.writeInt(phase.ordinal());
        out.writeInt(bitCount);
        out.writeInt(shiftValue);
        out.writeInt(command);
        out.writeBoolean(readDirection);
        out.writeInt(paramTotalBytes);
        out.writeInt(paramIndex);
        out.write(paramBuffer);
        out.writeBoolean(sioOut);
        out.writeInt(controlRegister);
    }

    public void loadState(DataInputStream in) throws IOException {
        prevSck = in.readBoolean();
        prevCs = in.readBoolean();
        phase = Phase.values()[in.readInt()];
        bitCount = in.readInt();
        shiftValue = in.readInt();
        command = in.readInt();
        readDirection = in.readBoolean();
        paramTotalBytes = in.readInt();
        paramIndex = in.readInt();
        in.readFully(paramBuffer);
        sioOut = in.readBoolean();
        controlRegister = in.readInt();
    }
}
