package dev.vitorsilverio.gbaemu.audio;

/// A read-only view of one sound channel for the audio debug panel.
///
/// @param name              display name (e.g. "CH1 Pulse", "Direct A")
/// @param enabled           whether the channel is currently producing sound
/// @param level             current level: envelope volume (PSG) or last sample (Direct)
/// @param frequencyHz       output frequency in Hz, or 0 when not applicable
/// @param muted             whether the user muted this channel in the debug panel
/// @param userVolumePercent the user's volume scaling for this channel (0-100)
/// @param detail            free-form extra state for diagnosis
public record GbaAudioChannelSnapshot(
        String name,
        boolean enabled,
        int level,
        double frequencyHz,
        boolean muted,
        int userVolumePercent,
        String detail) {
}
