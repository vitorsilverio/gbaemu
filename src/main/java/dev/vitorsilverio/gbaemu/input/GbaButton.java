package dev.vitorsilverio.gbaemu.input;

/// Botoes do keypad do Game Boy Advance.
public enum GbaButton {
    A(0),
    B(1),
    SELECT(2),
    START(3),
    RIGHT(4),
    LEFT(5),
    UP(6),
    DOWN(7),
    R(8),
    L(9);

    private final int bit;

    GbaButton(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public int mask() {
        return 1 << bit;
    }
}
