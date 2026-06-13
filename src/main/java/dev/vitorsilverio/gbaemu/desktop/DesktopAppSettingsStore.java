package dev.vitorsilverio.gbaemu.desktop;

import java.util.prefs.Preferences;

/// Reads and writes {@link AppSettings} to the user's {@link Preferences} store, so the
/// emulator remembers scale, audio mute/volume and boot mode between launches without a
/// config file. Missing keys fall back to {@link AppSettings#defaults()}.
public final class DesktopAppSettingsStore {
    private static final String SCALE = "scale";
    private static final String MUTE_AUDIO = "muteAudio";
    private static final String SCANLINE = "scanlineRendering";
    private static final String DEBUG_VIDEO = "debugVideo";
    private static final String BIOS_PATH = "biosPath";
    private static final String BOOT_MODE = "bootMode";
    private static final String CHANNEL_VOLUME = "channelVolume";
    private static final String CHANNEL_MUTED = "channelMuted";

    private DesktopAppSettingsStore() {
    }

    public static AppSettings load(Preferences preferences) {
        AppSettings defaults = AppSettings.defaults();
        int[] volumes = new int[AppSettings.CHANNEL_COUNT];
        boolean[] muted = new boolean[AppSettings.CHANNEL_COUNT];
        for (int i = 0; i < AppSettings.CHANNEL_COUNT; i++) {
            volumes[i] = preferences.getInt(CHANNEL_VOLUME + i, defaults.channelVolumes()[i]);
            muted[i] = preferences.getBoolean(CHANNEL_MUTED + i, defaults.channelMuted()[i]);
        }
        return new AppSettings(
                preferences.getInt(SCALE, defaults.scale()),
                preferences.getBoolean(MUTE_AUDIO, defaults.muteAudio()),
                preferences.getBoolean(SCANLINE, defaults.scanlineRendering()),
                preferences.getBoolean(DEBUG_VIDEO, defaults.debugVideo()),
                preferences.get(BIOS_PATH, defaults.biosPath()),
                parseBootMode(preferences.get(BOOT_MODE, defaults.bootMode().name())),
                volumes,
                muted).normalized();
    }

    public static void save(Preferences preferences, AppSettings settings) {
        AppSettings normalized = settings.normalized();
        preferences.putInt(SCALE, normalized.scale());
        preferences.putBoolean(MUTE_AUDIO, normalized.muteAudio());
        preferences.putBoolean(SCANLINE, normalized.scanlineRendering());
        preferences.putBoolean(DEBUG_VIDEO, normalized.debugVideo());
        preferences.put(BIOS_PATH, normalized.biosPath());
        preferences.put(BOOT_MODE, normalized.bootMode().name());
        for (int i = 0; i < AppSettings.CHANNEL_COUNT; i++) {
            preferences.putInt(CHANNEL_VOLUME + i, normalized.channelVolumes()[i]);
            preferences.putBoolean(CHANNEL_MUTED + i, normalized.channelMuted()[i]);
        }
    }

    private static AppSettings.BootMode parseBootMode(String value) {
        try {
            return AppSettings.BootMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AppSettings.BootMode.NO_BIOS;
        }
    }
}
