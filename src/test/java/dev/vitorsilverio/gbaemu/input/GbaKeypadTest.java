package dev.vitorsilverio.gbaemu.input;

import dev.vitorsilverio.gbaemu.interrupt.GbaInterrupt;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaKeypadTest {
    @Test
    void keyinputStartsWithAllButtonsReleased() {
        GbaKeypad keypad = new GbaKeypad(null);

        assertEquals(0x03FF, keypad.readHalfWord(GbaKeypad.KEYINPUT));
    }

    @Test
    void pressingButtonClearsTheActiveLowBit() {
        GbaKeypad keypad = new GbaKeypad(null);

        keypad.press(GbaButton.A);
        keypad.press(GbaButton.START);

        assertTrue(keypad.pressed(GbaButton.A));
        assertEquals(0x03F6, keypad.readHalfWord(GbaKeypad.KEYINPUT));
    }

    @Test
    void releasingButtonSetsTheActiveLowBitAgain() {
        GbaKeypad keypad = new GbaKeypad(null);

        keypad.press(GbaButton.B);
        keypad.release(GbaButton.B);

        assertFalse(keypad.pressed(GbaButton.B));
        assertEquals(0x03FF, keypad.readHalfWord(GbaKeypad.KEYINPUT));
    }

    @Test
    void keycntOrConditionRequestsInterruptWhenAnySelectedButtonIsPressed() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaKeypad keypad = new GbaKeypad(interrupts);
        keypad.writeHalfWord(GbaKeypad.KEYCNT, (1 << 14) | GbaButton.A.mask() | GbaButton.B.mask());

        keypad.press(GbaButton.B);

        assertEquals(GbaInterrupt.KEYPAD.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }

    @Test
    void keycntAndConditionWaitsForAllSelectedButtons() {
        GbaInterruptController interrupts = new GbaInterruptController();
        GbaKeypad keypad = new GbaKeypad(interrupts);
        keypad.writeHalfWord(GbaKeypad.KEYCNT, (1 << 15) | (1 << 14) | GbaButton.A.mask() | GbaButton.B.mask());

        keypad.press(GbaButton.A);
        assertEquals(0, interrupts.readHalfWord(GbaInterruptController.IF));

        keypad.press(GbaButton.B);
        assertEquals(GbaInterrupt.KEYPAD.mask(), interrupts.readHalfWord(GbaInterruptController.IF));
    }
}
