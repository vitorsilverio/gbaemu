package dev.vitorsilverio.gbaemu.input;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;

/// Estado inicial do keypad do GBA.
///
/// KEYINPUT usa logica active-low: bit 0 significa botao pressionado.
public final class GbaKeypad {
    public static final int KEYINPUT = 0x04000130;
    public static final int KEYCNT = 0x04000132;

    private static final int KEY_MASK = 0x03FF;
    private static final int IRQ_ENABLE = 1 << 14;
    private static final int IRQ_AND_CONDITION = 1 << 15;

    private final AddressSpace memory;
    private final GbaInterruptController interrupts;
    private int pressedMask;

    public GbaKeypad(AddressSpace memory) {
        this(memory, null);
    }

    public GbaKeypad(AddressSpace memory, GbaInterruptController interrupts) {
        this.memory = memory;
        this.interrupts = interrupts;
        updateKeyInput();
    }

    public void press(GbaButton button) {
        setPressed(button, true);
    }

    public void release(GbaButton button) {
        setPressed(button, false);
    }

    public void setPressed(GbaButton button, boolean pressed) {
        if (pressed) {
            pressedMask |= button.mask();
        } else {
            pressedMask &= ~button.mask();
        }
        updateKeyInput();
        requestInterruptIfNeeded();
    }

    public boolean pressed(GbaButton button) {
        return (pressedMask & button.mask()) != 0;
    }

    public int pressedMask() {
        return pressedMask;
    }

    public int keyInput() {
        return memory.read16(KEYINPUT) & KEY_MASK;
    }

    private void updateKeyInput() {
        int keyInput = KEY_MASK & ~pressedMask;
        memory.write8(KEYINPUT, keyInput);
        memory.write8(KEYINPUT + 1, keyInput >>> 8);
    }

    private void requestInterruptIfNeeded() {
        if (interrupts == null) {
            return;
        }

        int keycnt = memory.read16(KEYCNT);
        if ((keycnt & IRQ_ENABLE) == 0) {
            return;
        }

        int selected = keycnt & KEY_MASK;
        if (selected == 0) {
            return;
        }

        boolean matches = (keycnt & IRQ_AND_CONDITION) != 0
                ? (pressedMask & selected) == selected
                : (pressedMask & selected) != 0;
        if (matches) {
            interrupts.request(GbaInterrupt.KEYPAD);
        }
    }
}
