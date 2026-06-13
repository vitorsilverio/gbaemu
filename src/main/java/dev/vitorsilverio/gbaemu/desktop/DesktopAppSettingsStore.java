package dev.vitorsilverio.gbaemu.desktop;

import java.util.prefs.Preferences;

/// Reads and writes {@link AppSettings} to the user's {@link Preferences} store, so the
/// emulator remembers scale, audio mute/volume, boot mode, control bindings, the chosen
/// gamepad and the multiplayer endpoint between launches. Missing keys fall back to
/// {@link AppSettings#defaults()}.
public final class DesktopAppSettingsStore {
    private static final String SCALE = "scale";
    private static final String MUTE_AUDIO = "muteAudio";
    private static final String SCANLINE = "scanlineRendering";
    private static final String DEBUG_VIDEO = "debugVideo";
    private static final String BIOS_PATH = "biosPath";
    private static final String BOOT_MODE = "bootMode";
    private static final String CHANNEL_VOLUME = "channelVolume";
    private static final String CHANNEL_MUTED = "channelMuted";
    private static final String CONTROLLER_KEY = "controllerKey";
    private static final String GAMEPAD_DEVICE_INDEX = "gamepadDeviceIndex";
    private static final String GAMEPAD_DEVICE_NAME = "gamepadDeviceName";
    private static final String GAMEPAD_DEADZONE = "gamepadDeadzone";
    private static final String GAMEPAD_MAPPING = "gamepadMapping";
    private static final String MP_TCP_HOST = "multiplayerTcpHost";
    private static final String MP_TCP_PORT = "multiplayerTcpPort";
    private static final String MP_HOST_MODE = "multiplayerHostMode";

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
        int[] keys = new int[AppSettings.BUTTON_COUNT];
        String[] mappings = new String[AppSettings.BUTTON_COUNT];
        AppSettings.GamepadConfig padDefaults = defaults.gamepadConfig();
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            keys[i] = preferences.getInt(CONTROLLER_KEY + i, defaults.controllerKeyCodes()[i]);
            mappings[i] = preferences.get(GAMEPAD_MAPPING + i, padDefaults.mappings()[i]);
        }
        AppSettings.GamepadConfig gamepad = new AppSettings.GamepadConfig(
                preferences.getInt(GAMEPAD_DEVICE_INDEX, padDefaults.deviceIndex()),
                preferences.get(GAMEPAD_DEVICE_NAME, padDefaults.deviceName()),
                preferences.getInt(GAMEPAD_DEADZONE, padDefaults.deadzonePercent()),
                mappings);
        return new AppSettings(
                preferences.getInt(SCALE, defaults.scale()),
                preferences.getBoolean(MUTE_AUDIO, defaults.muteAudio()),
                preferences.getBoolean(SCANLINE, defaults.scanlineRendering()),
                preferences.getBoolean(DEBUG_VIDEO, defaults.debugVideo()),
                preferences.get(BIOS_PATH, defaults.biosPath()),
                parseBootMode(preferences.get(BOOT_MODE, defaults.bootMode().name())),
                volumes,
                muted,
                keys,
                gamepad,
                preferences.get(MP_TCP_HOST, defaults.multiplayerTcpHost()),
                preferences.getInt(MP_TCP_PORT, defaults.multiplayerTcpPort()),
                preferences.getBoolean(MP_HOST_MODE, defaults.multiplayerHostMode())).normalized();
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
        AppSettings.GamepadConfig gamepad = normalized.gamepadConfig();
        for (int i = 0; i < AppSettings.BUTTON_COUNT; i++) {
            preferences.putInt(CONTROLLER_KEY + i, normalized.controllerKeyCodes()[i]);
            preferences.put(GAMEPAD_MAPPING + i, gamepad.mappings()[i]);
        }
        preferences.putInt(GAMEPAD_DEVICE_INDEX, gamepad.deviceIndex());
        preferences.put(GAMEPAD_DEVICE_NAME, gamepad.deviceName());
        preferences.putInt(GAMEPAD_DEADZONE, gamepad.deadzonePercent());
        preferences.put(MP_TCP_HOST, normalized.multiplayerTcpHost());
        preferences.putInt(MP_TCP_PORT, normalized.multiplayerTcpPort());
        preferences.putBoolean(MP_HOST_MODE, normalized.multiplayerHostMode());
    }

    private static AppSettings.BootMode parseBootMode(String value) {
        try {
            return AppSettings.BootMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return AppSettings.BootMode.NO_BIOS;
        }
    }
}
