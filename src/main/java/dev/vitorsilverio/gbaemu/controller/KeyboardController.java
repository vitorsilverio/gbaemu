package dev.vitorsilverio.gbaemu.controller;

import dev.vitorsilverio.gbaemu.desktop.AppSettings;
import dev.vitorsilverio.gbaemu.input.GbaButton;

import java.util.HashMap;
import java.util.Map;

/// Translates AWT key codes into GBA button state using the configured bindings. Key events
/// are fed in from the window's key dispatcher on the AWT thread (the only writer of
/// {@code pressedBits}); the emulator thread reads the volatile bitmask each frame.
public final class KeyboardController implements Controller {

    private volatile Map<Integer, GbaButton> bindings = Map.of();
    private volatile int pressedBits;

    public KeyboardController(AppSettings settings) {
        applySettings(settings);
    }

    /// Rebuilds the keycode->button map from settings and clears held state. Call on the AWT thread.
    public void applySettings(AppSettings settings) {
        Map<Integer, GbaButton> map = new HashMap<>();
        for (GbaButton button : GbaButton.values()) {
            int code = settings.controllerKeyCode(button.ordinal());
            if (code > 0) {
                map.putIfAbsent(code, button);
            }
        }
        bindings = Map.copyOf(map);
        pressedBits = 0;
    }

    /// Records a key press/release (AWT thread). Returns true if the key is bound to a GBA
    /// button (so the caller can consume the event); unmapped keys are ignored.
    public boolean setKey(int keyCode, boolean pressed) {
        GbaButton button = bindings.get(keyCode);
        if (button == null) {
            return false;
        }
        int mask = 1 << button.ordinal();
        if (pressed) {
            pressedBits |= mask;
        } else {
            pressedBits &= ~mask;
        }
        return true;
    }

    @Override
    public boolean isPressed(GbaButton button) {
        return (pressedBits & (1 << button.ordinal())) != 0;
    }
}
