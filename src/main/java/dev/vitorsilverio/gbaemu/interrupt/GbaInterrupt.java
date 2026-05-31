package dev.vitorsilverio.gbaemu.interrupt;

/// Bits de interrupcao do registrador IE/IF do Game Boy Advance.
public enum GbaInterrupt {
    VBLANK(0),
    HBLANK(1),
    VCOUNT(2),
    TIMER0(3),
    TIMER1(4),
    TIMER2(5),
    TIMER3(6),
    SERIAL(7),
    DMA0(8),
    DMA1(9),
    DMA2(10),
    DMA3(11),
    KEYPAD(12),
    GAME_PAK(13);

    private final int bit;

    GbaInterrupt(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public int mask() {
        return 1 << bit;
    }
}
