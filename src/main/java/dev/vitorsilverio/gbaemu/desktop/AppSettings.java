package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.input.GbaButton;

import java.awt.event.KeyEvent;
import java.util.Arrays;

/// Immutable user preferences for the desktop emulator. Persisted between runs by
/// {@link DesktopAppSettingsStore} and edited through {@link SettingsDialog}.
///
/// The per-channel audio arrays are 6 wide: indices 0-3 are the PSG channels
/// (CH1/CH2 pulse, CH3 wave, CH4 noise) and 4/5 are the Direct Sound A/B (FIFO)
/// channels, matching {@link dev.vitorsilverio.gbaemu.audio.GbaAudio}'s channel ids.
///
/// {@code controllerKeyCodes} is indexed by {@link GbaButton#ordinal()} (10 buttons:
/// A,B,SELECT,START,RIGHT,LEFT,UP,DOWN,R,L), as is {@link GamepadConfig#mappings()}.
public record AppSettings(
        int scale,
        boolean muteAudio,
        boolean scanlineRendering,
        boolean debugVideo,
        String biosPath,
        BootMode bootMode,
        int[] channelVolumes,
        boolean[] channelMuted,
        int[] controllerKeyCodes,
        GamepadConfig gamepadConfig,
        String multiplayerTcpHost,
        int multiplayerTcpPort,
        boolean multiplayerHostMode,
        CpuBackend cpuBackend) {

    public static final int CHANNEL_COUNT = 6;
    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 8;
    public static final int BUTTON_COUNT = GbaButton.values().length;
    /// Human labels for each button, indexed by {@link GbaButton#ordinal()}.
    public static final String[] BUTTON_NAMES =
            {"A", "B", "Select", "Start", "Right", "Left", "Up", "Down", "R", "L"};
    public static final String DEFAULT_TCP_HOST = "localhost";
    public static final int DEFAULT_TCP_PORT = 26800;

    /// One gamepad's configuration. {@code mappings} holds, per {@link GbaButton#ordinal()},
    /// a comma-separated list of input4j component tokens (e.g. {@code "A,CROSS,BUTTON_0"};
    /// axis tokens carry a {@code +}/{@code -} suffix). {@code deviceIndex < 0} disables the pad.
    public record GamepadConfig(int deviceIndex, String deviceName, int deadzonePercent, String[] mappings) {
    }

    /// Which CPU backend the console uses. Changing this requires a ROM reload.
    ///
    /// {@link #INTERPRETED} is the default: it is fast enough for full speed and never stalls.
    /// {@link #JIT} compiles hot blocks to JVM bytecode for higher peak performance, at the cost
    /// of occasional compilation hitches.
    public enum CpuBackend {
        INTERPRETED("Interpreted"),
        JIT("JIT (JVM bytecode)");

        private final String label;

        CpuBackend(String label) { this.label = label; }

        public String label() { return label; }

        @Override
        public String toString() { return label; }
    }

    /// How the console boots, and whether a BIOS image is used at all.
    public enum BootMode {
        /// No BIOS file: start directly in the ROM with skip-boot register state.
        NO_BIOS("No BIOS (skip boot)"),
        /// BIOS provided, skip-boot, but SWIs handled by our HLE implementation.
        HLE("Skip boot + HLE SWI"),
        /// BIOS provided, run the real BIOS boot animation and use its code.
        REAL_BIOS("Real BIOS boot"),
        /// Skip-boot, but every SWI traps to the real BIOS (diagnostic A/B).
        REAL_SWI("Skip boot + real BIOS SWI");

        private final String label;

        BootMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /// Convenience constructor that defaults the controller, gamepad, multiplayer and CPU backend fields.
    /// Kept for call sites and tests that only set the video/audio preferences.
    public AppSettings(int scale, boolean muteAudio, boolean scanlineRendering, boolean debugVideo,
                       String biosPath, BootMode bootMode, int[] channelVolumes, boolean[] channelMuted) {
        this(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode, channelVolumes, channelMuted,
                defaultControllerKeyCodes(), defaultGamepadConfig(), DEFAULT_TCP_HOST, DEFAULT_TCP_PORT, true,
                CpuBackend.INTERPRETED);
    }

    public static AppSettings defaults() {
        int[] volumes = new int[CHANNEL_COUNT];
        Arrays.fill(volumes, 100);
        return new AppSettings(
                3,
                false,
                true,
                false,
                "",
                BootMode.NO_BIOS,
                volumes,
                new boolean[CHANNEL_COUNT],
                defaultControllerKeyCodes(),
                defaultGamepadConfig(),
                DEFAULT_TCP_HOST,
                DEFAULT_TCP_PORT,
                true,
                CpuBackend.INTERPRETED);
    }

    /// Returns a copy with array fields defensively cloned and every value clamped to a
    /// legal range, so callers never have to validate the stored settings themselves.
    public AppSettings normalized() {
        int[] volumes = Arrays.copyOf(channelVolumes == null ? new int[0] : channelVolumes, CHANNEL_COUNT);
        boolean[] muted = Arrays.copyOf(channelMuted == null ? new boolean[0] : channelMuted, CHANNEL_COUNT);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            volumes[i] = clamp(volumes[i], 0, 100);
        }
        int[] keys = Arrays.copyOf(controllerKeyCodes == null ? new int[0] : controllerKeyCodes, BUTTON_COUNT);
        int[] keyDefaults = defaultControllerKeyCodes();
        for (int i = 0; i < BUTTON_COUNT; i++) {
            if (keys[i] <= 0) {
                keys[i] = keyDefaults[i];
            }
        }
        return new AppSettings(
                clamp(scale, MIN_SCALE, MAX_SCALE),
                muteAudio,
                scanlineRendering,
                debugVideo,
                biosPath == null ? "" : biosPath.trim(),
                bootMode == null ? BootMode.NO_BIOS : bootMode,
                volumes,
                muted,
                keys,
                normalizeGamepadConfig(gamepadConfig),
                isBlank(multiplayerTcpHost) ? DEFAULT_TCP_HOST : multiplayerTcpHost.trim(),
                clamp(multiplayerTcpPort, 1, 65535),
                multiplayerHostMode,
                cpuBackend == null ? CpuBackend.INTERPRETED : cpuBackend);
    }

    public int channelVolume(int channelOneBased) {
        return channelVolumes[channelOneBased - 1];
    }

    public boolean isChannelMuted(int channelOneBased) {
        return channelMuted[channelOneBased - 1];
    }

    /// The configured key code for a button, indexed by {@link GbaButton#ordinal()}.
    public int controllerKeyCode(int buttonOrdinal) {
        return controllerKeyCodes[buttonOrdinal];
    }

    public AppSettings withScale(int newScale) {
        return new AppSettings(newScale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                channelVolumes.clone(), channelMuted.clone(), controllerKeyCodes.clone(), gamepadConfig,
                multiplayerTcpHost, multiplayerTcpPort, multiplayerHostMode, cpuBackend);
    }

    public AppSettings withChannelVolume(int channelOneBased, int percent) {
        int[] copy = channelVolumes.clone();
        copy[channelOneBased - 1] = clamp(percent, 0, 100);
        return new AppSettings(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                copy, channelMuted.clone(), controllerKeyCodes.clone(), gamepadConfig,
                multiplayerTcpHost, multiplayerTcpPort, multiplayerHostMode, cpuBackend);
    }

    public AppSettings withChannelMuted(int channelOneBased, boolean muted) {
        boolean[] copy = channelMuted.clone();
        copy[channelOneBased - 1] = muted;
        return new AppSettings(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                channelVolumes.clone(), copy, controllerKeyCodes.clone(), gamepadConfig,
                multiplayerTcpHost, multiplayerTcpPort, multiplayerHostMode, cpuBackend);
    }

    public AppSettings withMultiplayer(String tcpHost, int tcpPort, boolean hostMode) {
        return new AppSettings(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                channelVolumes.clone(), channelMuted.clone(), controllerKeyCodes.clone(), gamepadConfig,
                tcpHost, tcpPort, hostMode, cpuBackend);
    }

    private static int[] defaultControllerKeyCodes() {
        int[] codes = new int[BUTTON_COUNT];
        codes[GbaButton.A.ordinal()] = KeyEvent.VK_X;
        codes[GbaButton.B.ordinal()] = KeyEvent.VK_Z;
        codes[GbaButton.SELECT.ordinal()] = KeyEvent.VK_SHIFT;
        codes[GbaButton.START.ordinal()] = KeyEvent.VK_ENTER;
        codes[GbaButton.RIGHT.ordinal()] = KeyEvent.VK_RIGHT;
        codes[GbaButton.LEFT.ordinal()] = KeyEvent.VK_LEFT;
        codes[GbaButton.UP.ordinal()] = KeyEvent.VK_UP;
        codes[GbaButton.DOWN.ordinal()] = KeyEvent.VK_DOWN;
        codes[GbaButton.R.ordinal()] = KeyEvent.VK_S;
        codes[GbaButton.L.ordinal()] = KeyEvent.VK_A;
        return codes;
    }

    private static GamepadConfig defaultGamepadConfig() {
        return new GamepadConfig(0, "", 35, defaultGamepadMappings());
    }

    private static String[] defaultGamepadMappings() {
        String[] m = new String[BUTTON_COUNT];
        m[GbaButton.A.ordinal()] = "A,CROSS,BUTTON_0";
        m[GbaButton.B.ordinal()] = "B,CIRCLE,BUTTON_1";
        m[GbaButton.SELECT.ordinal()] = "BACK,SELECT,SHARE,BUTTON_6,BUTTON_8";
        m[GbaButton.START.ordinal()] = "START,OPTIONS,BUTTON_7,BUTTON_9";
        m[GbaButton.RIGHT.ordinal()] = "DPAD_RIGHT,LEFT_THUMB_X+,LEFT_AXIS_X+,AXIS_X+";
        m[GbaButton.LEFT.ordinal()] = "DPAD_LEFT,LEFT_THUMB_X-,LEFT_AXIS_X-,AXIS_X-";
        m[GbaButton.UP.ordinal()] = "DPAD_UP,LEFT_THUMB_Y-,LEFT_AXIS_Y-,AXIS_Y-";
        m[GbaButton.DOWN.ordinal()] = "DPAD_DOWN,LEFT_THUMB_Y+,LEFT_AXIS_Y+,AXIS_Y+";
        m[GbaButton.R.ordinal()] = "RIGHT_SHOULDER,BUTTON_5,RIGHT_TRIGGER+";
        m[GbaButton.L.ordinal()] = "LEFT_SHOULDER,BUTTON_4,LEFT_TRIGGER+";
        return m;
    }

    private static GamepadConfig normalizeGamepadConfig(GamepadConfig config) {
        GamepadConfig fallback = defaultGamepadConfig();
        if (config == null) {
            return fallback;
        }
        String[] mappings = Arrays.copyOf(
                config.mappings() == null ? fallback.mappings() : config.mappings(), BUTTON_COUNT);
        for (int i = 0; i < mappings.length; i++) {
            mappings[i] = isBlank(mappings[i]) ? fallback.mappings()[i] : mappings[i].trim();
        }
        return new GamepadConfig(
                clamp(config.deviceIndex(), -1, 15),
                config.deviceName() == null ? "" : config.deviceName().trim(),
                clamp(config.deadzonePercent(), 0, 95),
                mappings);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
