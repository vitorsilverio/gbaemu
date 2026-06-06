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
    void soundControlHResetBitsClearFifosAndAreNotStored() {
        GbaAudio audio = new GbaAudio();

        audio.writeWord(GbaAudio.FIFO_A, 0x44332211);
        audio.writeWord(GbaAudio.FIFO_B, 0x88776655);
        audio.writeHalfWord(GbaAudio.SOUNDCNT_H, 0x8C00);

        assertEquals(0, audio.fifoASize());
        assertEquals(0, audio.fifoBSize());
        assertEquals(0x0400, audio.readHalfWord(GbaAudio.SOUNDCNT_H));
    }
}
