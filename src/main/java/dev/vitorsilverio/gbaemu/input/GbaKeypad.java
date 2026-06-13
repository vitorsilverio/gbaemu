package dev.vitorsilverio.gbaemu.input;

import dev.vitorsilverio.gbaemu.core.MemorySpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Estado do keypad do GBA.
///
/// KEYINPUT usa logica active-low: bit 0 significa botao pressionado.
public final class GbaKeypad implements MemorySpace {
    public static final int KEYINPUT = 0x04000130;
    public static final int KEYCNT   = 0x04000132;

    private static final int KEY_MASK        = 0x03FF;
    private static final int IRQ_ENABLE      = 1 << 14;
    private static final int IRQ_AND_CONDITION = 1 << 15;

    private final GbaInterruptController interrupts;
    private int pressedMask;
    private int keycnt;

    public GbaKeypad(GbaInterruptController interrupts) {
        this.interrupts = interrupts;
    }

    @Override
    public boolean contains(int address) {
        return address >= KEYINPUT && address <= KEYCNT + 1;
    }

    @Override
    public int readByte(int address) {
        return readHalfWord(address & ~1) >>> ((address & 1) * 8) & 0xFF;
    }

    @Override
    public int readHalfWord(int address) {
        if ((address & ~1) == KEYINPUT) return KEY_MASK & ~pressedMask;
        if ((address & ~1) == KEYCNT)   return keycnt & 0xFFFF;
        return 0;
    }

    @Override
    public void writeByte(int address, int value) {
        if ((address & ~1) == KEYCNT) {
            int shift = (address & 1) * 8;
            int mask = 0xFF << shift;
            keycnt = (keycnt & ~mask) | ((value & 0xFF) << shift);
        }
    }

    @Override
    public void writeHalfWord(int address, int value) {
        if ((address & ~1) == KEYCNT) keycnt = value & 0xFFFF;
    }

    public void press(GbaButton button) {
        setPressed(button, true);
    }

    public void release(GbaButton button) {
        setPressed(button, false);
    }

    public void setPressed(GbaButton button, boolean pressed) {
        if (pressed) pressedMask |= button.mask();
        else pressedMask &= ~button.mask();
        requestInterruptIfNeeded();
    }

    public boolean pressed(GbaButton button) {
        return (pressedMask & button.mask()) != 0;
    }

    public int pressedMask() {
        return pressedMask;
    }

    /// Serializes KEYCNT into a save state. The pressed buttons (KEYINPUT) are live input,
    /// not part of the snapshot, so reloading keeps whatever the player is currently holding.
    public void saveState(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(keycnt);
    }

    /// Restores KEYCNT from a save state.
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        keycnt = in.readInt();
    }

    private void requestInterruptIfNeeded() {
        if (interrupts == null || (keycnt & IRQ_ENABLE) == 0) return;
        int selected = keycnt & KEY_MASK;
        if (selected == 0) return;
        boolean matches = (keycnt & IRQ_AND_CONDITION) != 0
                ? (pressedMask & selected) == selected
                : (pressedMask & selected) != 0;
        if (matches) interrupts.request(GbaInterrupt.KEYPAD);
    }
}
