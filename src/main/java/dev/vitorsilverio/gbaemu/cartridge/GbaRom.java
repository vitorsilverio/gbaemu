package dev.vitorsilverio.gbaemu.cartridge;

import dev.vitorsilverio.gbaemu.cartridge.rtc.S3511aRtc;
import dev.vitorsilverio.gbaemu.core.MemorySpace;

import java.util.Arrays;
import java.util.Objects;

/// ROM do cartucho mapeada nas tres regioes de waitstate (WS0/WS1/WS2).
public final class GbaRom implements MemorySpace {
    private static final int START = 0x08000000;
    private static final int END   = 0x0DFFFFFF;
    public static final int MAX_SIZE = 32 * 1024 * 1024;

    /// GBATEK "GBA Cart I/O Port (GPIO)": janela de 6 bytes mapeada DENTRO da ROM (a
    /// própria ROM continua zero-preenchida nesses offsets) usada pelo chip RTC S-3511A
    /// (task D1) e por outros periféricos de cartucho fora do escopo (rumble, sensores).
    /// Só é interceptada quando `rtc != null` (detecção por game code em
    /// [GbaRtcDetector]) — para os cartuchos sem RTC o comportamento é idêntico ao de
    /// antes desta task: byte cru da ROM (G3).
    private static final int GPIO_DATA      = 0x080000C4;
    private static final int GPIO_DIRECTION = 0x080000C6;
    private static final int GPIO_CONTROL   = 0x080000C8;
    private static final int GPIO_REGION_END = GPIO_CONTROL + 1; // 0x080000C9, ultimo byte da janela

    private static final int GPIO_PINS_MASK = 0xF; // bits0-3: os 4 pinos de dados do GPIO
    private static final int GPIO_ENABLE_MASK = 0x1; // bit0 do registrador de controle

    // GBATEK "Connection Examples": fiacao do RTC no GPIO do Boktai.
    private static final int PIN_SCK = 0;
    private static final int PIN_SIO = 1;
    private static final int PIN_CS  = 2;

    private final byte[] data;
    private final S3511aRtc rtc;
    private int openBusValue;
    private int gpioData;
    private int gpioDirection;
    private int gpioControl;

    public GbaRom(byte[] rom) {
        this(rom, null);
    }

    /// `rtc` não-nulo liga a janela de GPIO ao chip RTC do cartucho (task D1); `null`
    /// preserva o comportamento anterior (ROM comum, sem nenhum registrador especial).
    public GbaRom(byte[] rom, S3511aRtc rtc) {
        Objects.requireNonNull(rom, "rom");
        this.data = Arrays.copyOf(rom, Math.min(rom.length, MAX_SIZE));
        this.rtc = rtc;
    }

    public void setOpenBusValue(int value) {
        this.openBusValue = value;
    }

    /// Chip RTC ligado a este cartucho, ou `null` se o jogo não usa RTC (task D1).
    public S3511aRtc rtc() {
        return rtc;
    }

    @Override
    public boolean contains(int address) {
        return Integer.compareUnsigned(address, START) >= 0
                && Integer.compareUnsigned(address, END) <= 0;
    }

    @Override
    public int readByte(int address) {
        if (rtc != null && isGpioAddress(address) && (gpioControl & GPIO_ENABLE_MASK) != 0) {
            return readGpioByte(address);
        }
        int offset = romOffset(address);
        if (offset >= data.length) {
            return (openBusValue >>> ((address & 3) * 8)) & 0xFF;
        }
        return data[offset] & 0xFF;
    }

    @Override
    public void writeByte(int address, int value) {
        // ROM is read-only. GBATEK: "ROM-bus writes are limited to 16bit/32bit access
        // (STRB opcodes are ignored)" — this no-op already matches that for the GPIO
        // window too, so no special-casing is needed here.
    }

    @Override
    public void writeHalfWord(int address, int value) {
        if (rtc != null && isGpioAddress(address)) {
            writeGpioHalfWord(address, value & 0xFFFF);
        }
        // else: ROM is read-only, mirrors writeByte.
    }

    private boolean isGpioAddress(int address) {
        return Integer.compareUnsigned(address, GPIO_DATA) >= 0
                && Integer.compareUnsigned(address, GPIO_REGION_END) <= 0;
    }

    private int readGpioByte(int address) {
        boolean highByte = (address & 1) != 0;
        if (highByte) {
            return 0; // bits4-15 de todos os 3 registradores nao sao usados
        }
        int registerBase = address & ~1;
        return switch (registerBase) {
            case GPIO_DATA -> readGpioDataByte();
            case GPIO_DIRECTION -> gpioDirection & GPIO_PINS_MASK;
            case GPIO_CONTROL -> gpioControl & GPIO_ENABLE_MASK;
            default -> 0;
        };
    }

    /// Cada pino de saída (Direção=1) devolve o último valor escrito (latch simples); o
    /// pino SIO configurado como entrada (Direção=0) devolve o bit que o RTC está
    /// dirigindo de volta — os demais pinos de entrada não têm nenhum dispositivo ligado
    /// nesta implementação (só RTC, task D1) e leem 0.
    private int readGpioDataByte() {
        int value = 0;
        for (int pin = 0; pin < 4; pin++) {
            boolean output = ((gpioDirection >>> pin) & 1) != 0;
            boolean bit;
            if (output) {
                bit = ((gpioData >>> pin) & 1) != 0;
            } else if (pin == PIN_SIO) {
                bit = rtc.sioReadback();
            } else {
                bit = false;
            }
            if (bit) {
                value |= 1 << pin;
            }
        }
        return value;
    }

    private void writeGpioHalfWord(int address, int value) {
        int registerBase = address & ~1;
        switch (registerBase) {
            case GPIO_DATA -> {
                gpioData = value & GPIO_PINS_MASK;
                rtc.updatePins(bitSet(gpioData, PIN_SCK), bitSet(gpioData, PIN_SIO), bitSet(gpioData, PIN_CS));
            }
            case GPIO_DIRECTION -> gpioDirection = value & GPIO_PINS_MASK;
            case GPIO_CONTROL -> gpioControl = value & GPIO_ENABLE_MASK;
            default -> { /* escrita em endereço ímpar dentro da janela: sem registrador aqui */ }
        }
    }

    private static boolean bitSet(int value, int bit) {
        return ((value >>> bit) & 1) != 0;
    }

    private int romOffset(int address) {
        // All three waitstate mirrors map to the same physical ROM
        return (address - START) & (MAX_SIZE - 1);
    }
}
