package dev.vitorsilverio.gbaemu.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaAudioTest {
    @Test
    void soundBiasStartsAtHardwareResetValue() {
        GbaAudio audio = new GbaAudio();

        assertEquals(0x0200, audio.readHalfWord(GbaAudio.SOUNDBIAS));
        assertEquals(0x0200, audio.soundBias());
    }

    @Test
    void soundBiasKeepsOnlyWritableBits() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDBIAS, 0xFFFF);

        assertEquals(0xC3FE, audio.readHalfWord(GbaAudio.SOUNDBIAS));
    }

    @Test
    void soundMasterEnableIsReadableButChannelStatusBitsAreNotForcedOn() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x008F);

        assertTrue(audio.masterEnabled());
        assertEquals(0x0080, audio.readHalfWord(GbaAudio.SOUNDCNT_X));
    }

    @Test
    void clearingSoundMasterResetsSoundRegistersAndFifosButKeepsBiasDefault() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);
        audio.writeHalfWord(GbaAudio.SOUNDBIAS, 0x0400);
        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0);

        assertFalse(audio.masterEnabled());
        assertEquals(0, audio.fifoASize());
        assertEquals(0x0200, audio.readHalfWord(GbaAudio.SOUNDBIAS));
    }

    @Test
    void fifoWritesAreAcceptedInLittleEndianOrderAndReadsReturnOpenSilence() {
        GbaAudio audio = new GbaAudio();

        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);
        audio.writeHalfWord(GbaAudio.FIFO_B, 0x6655);

        assertEquals(4, audio.fifoASize());
        assertEquals(2, audio.fifoBSize());
        assertEquals(0x11, audio.popFifoA());
        assertEquals(0x22, audio.popFifoA());
        assertEquals(0x33, audio.popFifoA());
        assertEquals(0x44, audio.popFifoA());
        assertEquals(0x55, audio.popFifoB());
        assertEquals(0x66, audio.popFifoB());
        assertEquals(0, audio.readByte(GbaAudio.FIFO_A));
    }

    @Test
    void timerOverflowConsumesDirectSoundFifoAndRequestsRefill() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0304);
        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);

        assertEquals(GbaAudio.FIFO_A_REQUEST, audio.timerOverflow(1));
        assertEquals(3, audio.fifoASize());
        audio.tick(512);
        assertEquals(1, audio.drainPcm(4).length);
    }

    @Test
    void directSoundHoldsLastSampleWhenFifoRunsDry() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0304);
        audio.writeByte(GbaAudio.FIFO_A, 0x40);

        audio.timerOverflow(1);
        audio.tick(512);
        audio.timerOverflow(1);
        audio.tick(512);

        byte[] samples = audio.drainPcm(2);
        assertEquals(2, samples.length);
        assertEquals(0x40, samples[0]);
        assertEquals(0x40, samples[1]);
    }

    @Test
    void timerOverflowCanConsumeMultipleDirectSoundSamples() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0304);
        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);

        audio.timerOverflow(1, 3, 0);

        assertEquals(1, audio.fifoASize());
        audio.timerOverflow(1, 1, 0);
        audio.tick(512);
        byte[] samples = audio.drainPcm(1);
        assertEquals(1, samples.length);
        assertEquals(0x44, samples[0]);
    }

    @Test
    void timerOverflowIgnoresDisabledDirectSoundOutputs() {
        GbaAudio audio = new GbaAudio();

        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);
        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);

        assertEquals(0, audio.timerOverflow(1));
        assertEquals(4, audio.fifoASize());
        assertEquals(0, audio.drainPcm(4).length);
    }

    @Test
    void idlePsgChannelsStayNearSilenceInsteadOfRailingTheMix() {
        // Reproduces the pokefirered symptom: channels routed at full master volume
        // but sitting idle (DAC enabled, envelope volume 0). A correct DAC outputs the
        // digital level 0 -> silence; the old "15 - 2*digital" mapping turned idle
        // channels into a constant +15 that railed the whole mix to +127.
        GbaAudio audio = new GbaAudio();
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);   // master enable
        audio.writeHalfWord(GbaAudio.SOUNDCNT_L, 0x7777);   // all PSG channels L+R, max master volume
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0002);   // PSG ratio 100%, no Direct Sound
        // Enable pulse channels 1 and 2 with envelope volume 0 but the DAC powered on
        // (envelope byte 0x08 = initial volume 0, increase direction).
        audio.writeHalfWord(GbaAudio.SOUND1CNT_H, 0x0800);
        audio.writeHalfWord(GbaAudio.SOUND1CNT_X, 0x8000);  // trigger ch1
        audio.writeHalfWord(GbaAudio.SOUND2CNT_L, 0x0800);
        audio.writeHalfWord(GbaAudio.SOUND2CNT_H, 0x8000);  // trigger ch2

        audio.tick(512 * 64);
        byte[] samples = audio.drainPcm(64);

        assertTrue(samples.length > 0, "expected mixed samples");
        for (byte sample : samples) {
            assertTrue(Math.abs(sample) <= 8,
                    "idle PSG channels must stay near silence, got " + sample);
        }
    }

    @Test
    void activePsgSquareWaveOscillatesAroundZeroWithoutDcOffset() {
        GbaAudio audio = new GbaAudio();
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);   // master enable
        audio.writeHalfWord(GbaAudio.SOUNDCNT_L, 0x2277);   // channel 2 L+R, max master volume
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0002);   // PSG ratio 100%, no Direct Sound
        audio.writeHalfWord(GbaAudio.SOUND2CNT_L, 0xF080);  // duty, envelope volume 15
        audio.writeHalfWord(GbaAudio.SOUND2CNT_H, 0x8400);  // trigger, period 1024 (128 Hz)

        // Tick in small batches, the way the console drives audio per CPU block, so the
        // duty step is sampled over time instead of frozen at the end of a giant batch.
        for (int i = 0; i < 2048; i++) {
            audio.tick(64);
        }
        byte[] samples = audio.drainPcm(256);

        boolean positive = false;
        boolean negative = false;
        for (int i = 32; i < samples.length; i++) { // skip the high-pass settling transient
            if (samples[i] > 4) positive = true;
            if (samples[i] < -4) negative = true;
        }
        assertTrue(positive && negative,
                "an active square wave must swing both above and below zero (DC removed)");
    }

    @Test
    void pulseChannelPlaysAtGbaTunedFrequencyNotFourTimesTooHigh() {
        GbaAudio audio = new GbaAudio();
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);   // master enable
        audio.writeHalfWord(GbaAudio.SOUNDCNT_L, 0x2277);   // channel 2 L+R, max master volume
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0002);   // PSG ratio 100%, no Direct Sound
        audio.writeHalfWord(GbaAudio.SOUND2CNT_L, 0xF080);  // duty, envelope volume 15
        audio.writeHalfWord(GbaAudio.SOUND2CNT_H, 0x8400);  // trigger, period 1024 => 128 Hz

        // Half a second of audio. Frequency = 131072/(2048-1024) = 128 Hz, so we expect
        // ~64 wave cycles. The pre-fix bug ran the frequency timer on the Game Boy clock
        // (4x too fast) and produced ~256 cycles — this guards that regression.
        for (int cycle = 0; cycle < 16_777_216 / 2; cycle += 128) {
            audio.tick(128);
        }
        byte[] samples = audio.drainPcm(16_384);

        int waveCycles = 0;
        boolean high = false;
        for (int i = 64; i < samples.length; i++) { // skip the high-pass settling transient
            if (!high && samples[i] > 4) {
                waveCycles++;
                high = true;
            } else if (high && samples[i] < -4) {
                high = false;
            }
        }
        assertTrue(waveCycles >= 30 && waveCycles <= 150,
                "expected ~64 wave cycles for a 128 Hz square (not ~256 from the 4x bug), got " + waveCycles);
    }

    @Test
    void soundControlHResetBitsClearFifosAndAreNotStored() {
        GbaAudio audio = new GbaAudio();

        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);
        audio.writeWord(GbaAudio.FIFO_B, 0x88776655);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x8C00);

        assertEquals(0, audio.fifoASize());
        assertEquals(0, audio.fifoBSize());
        assertEquals(0x0400, audio.readHalfWord(GbaAudio.SOUNDCNT_H));
    }

    @Test
    void mutingPsgChannelSilencesItEvenWhenActive() {
        GbaAudio audio = new GbaAudio();
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);   // master enable
        audio.writeHalfWord(GbaAudio.SOUNDCNT_L, 0x2277);   // channel 2 L+R, max master volume
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x0002);   // PSG ratio 100%, no Direct Sound
        audio.writeHalfWord(GbaAudio.SOUND2CNT_L, 0xF080);  // duty 50%, envelope volume 15
        audio.writeHalfWord(GbaAudio.SOUND2CNT_H, 0x8400);  // trigger, period 1024
        audio.setChannelMuted(2, true);

        for (int i = 0; i < 2048; i++) {
            audio.tick(64);
        }
        byte[] samples = audio.drainPcm(256);

        assertTrue(samples.length > 0, "expected mixed samples");
        for (byte sample : samples) {
            assertEquals(0, sample, "a muted channel must contribute nothing to the mix");
        }
    }

    @Test
    void channelVolumeClampsAndUserControlsRoundTrip() {
        GbaAudio audio = new GbaAudio();

        audio.setChannelVolume(1, 250);
        assertEquals(100, audio.channelVolume(1));
        audio.setChannelVolume(1, -5);
        assertEquals(0, audio.channelVolume(1));
        audio.setChannelVolume(3, 60);
        assertEquals(60, audio.channelVolume(3));

        assertFalse(audio.channelMuted(6));
        audio.setChannelMuted(6, true);
        assertTrue(audio.channelMuted(6));
    }

    @Test
    void debugSnapshotReportsAllSixChannelsAndUserControls() {
        GbaAudio audio = new GbaAudio();
        audio.writeHalfWord(GbaAudio.SOUNDCNT_X, 0x0080);   // master enable
        audio.setChannelMuted(3, true);
        audio.setChannelVolume(4, 40);

        GbaAudioSnapshot snapshot = audio.debugSnapshot();

        assertTrue(snapshot.masterEnabled());
        assertEquals(6, snapshot.channels().size());
        assertEquals("CH1 Pulse", snapshot.channels().get(0).name());
        assertEquals("CH3 Wave", snapshot.channels().get(2).name());
        assertTrue(snapshot.channels().get(2).muted());
        assertEquals(40, snapshot.channels().get(3).userVolumePercent());
        assertEquals("Direct A", snapshot.channels().get(4).name());
        assertEquals("Direct B", snapshot.channels().get(5).name());
    }
}
