package dev.vitorsilverio.gbaemu.desktop;

import java.util.Arrays;

/// Immutable user preferences for the desktop emulator. Persisted between runs by
/// {@link DesktopAppSettingsStore} and edited through {@link SettingsDialog}.
///
/// The per-channel audio arrays are 6 wide: indices 0-3 are the PSG channels
/// (CH1/CH2 pulse, CH3 wave, CH4 noise) and 4/5 are the Direct Sound A/B (FIFO)
/// channels, matching {@link dev.vitorsilverio.gbaemu.audio.GbaAudio}'s channel ids.
public record AppSettings(
        int scale,
        boolean muteAudio,
        boolean scanlineRendering,
        boolean debugVideo,
        String biosPath,
        BootMode bootMode,
        int[] channelVolumes,
        boolean[] channelMuted) {

    public static final int CHANNEL_COUNT = 6;
    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 8;

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
                new boolean[CHANNEL_COUNT]);
    }

    /// Returns a copy with array fields defensively cloned and every value clamped to a
    /// legal range, so callers never have to validate the stored settings themselves.
    public AppSettings normalized() {
        int[] volumes = Arrays.copyOf(channelVolumes == null ? new int[0] : channelVolumes, CHANNEL_COUNT);
        boolean[] muted = Arrays.copyOf(channelMuted == null ? new boolean[0] : channelMuted, CHANNEL_COUNT);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            volumes[i] = clamp(volumes[i], 0, 100);
        }
        return new AppSettings(
                clamp(scale, MIN_SCALE, MAX_SCALE),
                muteAudio,
                scanlineRendering,
                debugVideo,
                biosPath == null ? "" : biosPath.trim(),
                bootMode == null ? BootMode.NO_BIOS : bootMode,
                volumes,
                muted);
    }

    public int channelVolume(int channelOneBased) {
        return channelVolumes[channelOneBased - 1];
    }

    public boolean isChannelMuted(int channelOneBased) {
        return channelMuted[channelOneBased - 1];
    }

    public AppSettings withScale(int newScale) {
        return new AppSettings(newScale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                channelVolumes.clone(), channelMuted.clone());
    }

    public AppSettings withChannelVolume(int channelOneBased, int percent) {
        int[] copy = channelVolumes.clone();
        copy[channelOneBased - 1] = clamp(percent, 0, 100);
        return new AppSettings(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                copy, channelMuted.clone());
    }

    public AppSettings withChannelMuted(int channelOneBased, boolean muted) {
        boolean[] copy = channelMuted.clone();
        copy[channelOneBased - 1] = muted;
        return new AppSettings(scale, muteAudio, scanlineRendering, debugVideo, biosPath, bootMode,
                channelVolumes.clone(), copy);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
