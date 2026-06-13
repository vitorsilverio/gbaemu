package dev.vitorsilverio.gbaemu.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {
    @Test
    void defaultsAreSane() {
        AppSettings defaults = AppSettings.defaults();

        assertEquals(3, defaults.scale());
        assertFalse(defaults.muteAudio());
        assertTrue(defaults.scanlineRendering());
        assertEquals(AppSettings.BootMode.NO_BIOS, defaults.bootMode());
        assertEquals(AppSettings.CHANNEL_COUNT, defaults.channelVolumes().length);
        for (int channel = 1; channel <= AppSettings.CHANNEL_COUNT; channel++) {
            assertEquals(100, defaults.channelVolume(channel));
            assertFalse(defaults.isChannelMuted(channel));
        }
    }

    @Test
    void normalizedClampsScaleAndVolumes() {
        AppSettings settings = new AppSettings(
                99, false, true, false, "  ", AppSettings.BootMode.HLE,
                new int[]{-10, 250, 50, 100, 100, 100},
                new boolean[AppSettings.CHANNEL_COUNT]).normalized();

        assertEquals(AppSettings.MAX_SCALE, settings.scale());
        assertEquals(0, settings.channelVolume(1));
        assertEquals(100, settings.channelVolume(2));
        assertEquals(50, settings.channelVolume(3));
        assertEquals("", settings.biosPath());
    }

    @Test
    void normalizedPadsShortChannelArrays() {
        AppSettings settings = new AppSettings(
                3, false, true, false, "", AppSettings.BootMode.NO_BIOS,
                new int[]{75}, new boolean[]{true}).normalized();

        assertEquals(AppSettings.CHANNEL_COUNT, settings.channelVolumes().length);
        assertEquals(AppSettings.CHANNEL_COUNT, settings.channelMuted().length);
        assertEquals(75, settings.channelVolume(1));
        assertTrue(settings.isChannelMuted(1));
        assertEquals(0, settings.channelVolume(6));
    }

    @Test
    void withChannelHelpersReturnIndependentCopies() {
        AppSettings base = AppSettings.defaults();

        AppSettings muted = base.withChannelMuted(2, true);
        AppSettings scaled = base.withChannelVolume(2, 30);

        assertFalse(base.isChannelMuted(2), "the original must not be mutated");
        assertEquals(100, base.channelVolume(2));
        assertTrue(muted.isChannelMuted(2));
        assertEquals(30, scaled.channelVolume(2));
    }
}
