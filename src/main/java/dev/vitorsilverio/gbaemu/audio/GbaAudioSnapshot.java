package dev.vitorsilverio.gbaemu.audio;

import java.util.List;

/// A read-only view of the whole sound unit for the audio debug panel: the master
/// control registers, mixer status and the per-channel snapshots.
public record GbaAudioSnapshot(
        int soundCntL,
        int soundCntH,
        int soundCntX,
        int soundBias,
        boolean masterEnabled,
        int sampleRate,
        int bufferedSamples,
        int fifoASize,
        int fifoBSize,
        List<GbaAudioChannelSnapshot> channels) {
}
