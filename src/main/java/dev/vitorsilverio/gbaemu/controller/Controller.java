package dev.vitorsilverio.gbaemu.controller;

import dev.vitorsilverio.gbaemu.input.GbaButton;

/// A source of GBA button state. Implementations are polled once per frame by the emulator
/// thread (see {@code GbaEmulator}); they must therefore be safe to read from another thread
/// while a UI thread (keyboard events) or a poll thread (gamepad) updates them.
public interface Controller {

    /// Whether the given GBA button is currently held.
    boolean isPressed(GbaButton button);
}
