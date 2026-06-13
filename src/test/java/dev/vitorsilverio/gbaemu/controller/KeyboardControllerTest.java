package dev.vitorsilverio.gbaemu.controller;

import dev.vitorsilverio.gbaemu.desktop.AppSettings;
import dev.vitorsilverio.gbaemu.input.GbaButton;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardControllerTest {

    @Test
    void defaultBindingMapsKeyToButtonAndReleases() {
        KeyboardController controller = new KeyboardController(AppSettings.defaults());

        assertFalse(controller.isPressed(GbaButton.A));
        assertTrue(controller.setKey(KeyEvent.VK_X, true), "VK_X is the default A binding");
        assertTrue(controller.isPressed(GbaButton.A));

        controller.setKey(KeyEvent.VK_X, false);
        assertFalse(controller.isPressed(GbaButton.A));
    }

    @Test
    void unboundKeyIsIgnored() {
        KeyboardController controller = new KeyboardController(AppSettings.defaults());

        assertFalse(controller.setKey(KeyEvent.VK_F1, true));
        for (GbaButton button : GbaButton.values()) {
            assertFalse(controller.isPressed(button));
        }
    }

    @Test
    void rebindingAppliesAndOldKeyStopsWorking() {
        AppSettings defaults = AppSettings.defaults();
        int[] keys = defaults.controllerKeyCodes().clone();
        keys[GbaButton.A.ordinal()] = KeyEvent.VK_Q;
        AppSettings rebound = new AppSettings(
                defaults.scale(), defaults.muteAudio(), defaults.scanlineRendering(), defaults.debugVideo(),
                defaults.biosPath(), defaults.bootMode(), defaults.channelVolumes(), defaults.channelMuted(),
                keys, defaults.gamepadConfig(), defaults.multiplayerTcpHost(), defaults.multiplayerTcpPort(),
                defaults.multiplayerHostMode()).normalized();

        KeyboardController controller = new KeyboardController(rebound);
        assertTrue(controller.setKey(KeyEvent.VK_Q, true));
        assertTrue(controller.isPressed(GbaButton.A));
        assertFalse(controller.setKey(KeyEvent.VK_X, true), "the old A key is no longer bound");
    }
}
