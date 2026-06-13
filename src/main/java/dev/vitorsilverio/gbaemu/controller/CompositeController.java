package dev.vitorsilverio.gbaemu.controller;

import dev.vitorsilverio.gbaemu.input.GbaButton;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Merges several controllers (keyboard + gamepad) by OR-ing their button state, so the player
/// can use either at any time. Closing it closes any {@link AutoCloseable} members (the gamepad
/// poll thread).
public final class CompositeController implements Controller, AutoCloseable {

    private final List<Controller> controllers;

    public CompositeController(Controller... controllers) {
        this.controllers = Arrays.stream(controllers).filter(Objects::nonNull).toList();
    }

    @Override
    public boolean isPressed(GbaButton button) {
        for (Controller controller : controllers) {
            if (controller.isPressed(button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (Controller controller : controllers) {
            if (controller instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
