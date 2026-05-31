package dev.vitorsilverio.gbaemu.input;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaKeypadTest {
    @Test
    void keyinputStartsWithAllButtonsReleased() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaKeypad keypad = new GbaKeypad(memory);

        assertEquals(0x03FF, keypad.keyInput());
    }

    @Test
    void pressingButtonClearsTheActiveLowBit() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaKeypad keypad = new GbaKeypad(memory);

        keypad.press(GbaButton.A);
        keypad.press(GbaButton.START);

        assertTrue(keypad.pressed(GbaButton.A));
        assertEquals(0x03F6, memory.read16(GbaKeypad.KEYINPUT));
    }

    @Test
    void releasingButtonSetsTheActiveLowBitAgain() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaKeypad keypad = new GbaKeypad(memory);

        keypad.press(GbaButton.B);
        keypad.release(GbaButton.B);

        assertFalse(keypad.pressed(GbaButton.B));
        assertEquals(0x03FF, memory.read16(GbaKeypad.KEYINPUT));
    }

    @Test
    void keycntOrConditionRequestsInterruptWhenAnySelectedButtonIsPressed() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        GbaKeypad keypad = new GbaKeypad(memory, interrupts);
        memory.write16(GbaKeypad.KEYCNT, (1 << 14) | GbaButton.A.mask() | GbaButton.B.mask());

        keypad.press(GbaButton.B);

        assertEquals(GbaInterrupt.KEYPAD.mask(), memory.read16(GbaInterruptController.IF));
    }

    @Test
    void keycntAndConditionWaitsForAllSelectedButtons() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        GbaKeypad keypad = new GbaKeypad(memory, interrupts);
        memory.write16(GbaKeypad.KEYCNT, (1 << 15) | (1 << 14) | GbaButton.A.mask() | GbaButton.B.mask());

        keypad.press(GbaButton.A);
        assertEquals(0, memory.read16(GbaInterruptController.IF));

        keypad.press(GbaButton.B);
        assertEquals(GbaInterrupt.KEYPAD.mask(), memory.read16(GbaInterruptController.IF));
    }
}
