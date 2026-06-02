package dev.vitorsilverio.gbaemu.audio;

import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaAudioTest {
    @Test
    void soundBiasStartsAtHardwareResetValue() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        assertEquals(0x0200, memory.read16(GbaAudio.SOUNDBIAS));
        assertEquals(0x0200, memory.audio().soundBias());
    }

    @Test
    void soundBiasKeepsOnlyWritableBits() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDBIAS, 0xFFFF);

        assertEquals(0xC3FE, memory.read16(GbaAudio.SOUNDBIAS));
    }

    @Test
    void soundMasterEnableIsReadableButChannelStatusBitsAreNotForcedOn() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x008F);

        assertTrue(memory.audio().masterEnabled());
        assertEquals(0x0080, memory.read16(GbaAudio.SOUNDCNT_X));
    }

    @Test
    void clearingSoundMasterResetsSoundRegistersAndFifosButKeepsBiasDefault() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x0080);
        memory.write16(GbaAudio.SOUNDBIAS, 0x0400);
        memory.write32(GbaAudio.FIFO_A, 0x44332211);
        memory.write16(GbaAudio.SOUNDCNT_X, 0);

        assertFalse(memory.audio().masterEnabled());
        assertEquals(0, memory.audio().fifoASize());
        assertEquals(0x0200, memory.read16(GbaAudio.SOUNDBIAS));
    }

    @Test
    void fifoWritesAreAcceptedInLittleEndianOrderAndReadsReturnOpenSilence() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(GbaAudio.FIFO_A, 0x44332211);
        memory.write16(GbaAudio.FIFO_B, 0x6655);

        assertEquals(4, memory.audio().fifoASize());
        assertEquals(2, memory.audio().fifoBSize());
        assertEquals(0x11, memory.audio().popFifoA());
        assertEquals(0x22, memory.audio().popFifoA());
        assertEquals(0x33, memory.audio().popFifoA());
        assertEquals(0x44, memory.audio().popFifoA());
        assertEquals(0x55, memory.audio().popFifoB());
        assertEquals(0x66, memory.audio().popFifoB());
        assertEquals(0, memory.read8(GbaAudio.FIFO_A));
    }

    @Test
    void timerOverflowConsumesDirectSoundFifoAndRequestsRefill() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x0080);
        memory.write16(GbaAudio.SOUNDCNT_H, 0x0304);
        memory.write32(GbaAudio.FIFO_A, 0x44332211);

        assertEquals(GbaAudio.FIFO_A_REQUEST, memory.audio().timerOverflow(1));
        assertEquals(3, memory.audio().fifoASize());
        memory.audio().tick(512);
        assertEquals(1, memory.audio().drainPcm(4).length);
    }

    @Test
    void directSoundHoldsLastSampleWhenFifoRunsDry() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x0080);
        memory.write16(GbaAudio.SOUNDCNT_H, 0x0304);
        memory.write8(GbaAudio.FIFO_A, 0x40);

        memory.audio().timerOverflow(1);
        memory.audio().tick(512);
        memory.audio().timerOverflow(1);
        memory.audio().tick(512);

        byte[] samples = memory.audio().drainPcm(2);
        assertEquals(2, samples.length);
        assertEquals(0x40, samples[0]);
        assertEquals(0x40, samples[1]);
    }

    @Test
    void timerOverflowCanConsumeMultipleDirectSoundSamples() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x0080);
        memory.write16(GbaAudio.SOUNDCNT_H, 0x0304);
        memory.write32(GbaAudio.FIFO_A, 0x44332211);

        memory.audio().timerOverflow(1, 3, 0);

        assertEquals(1, memory.audio().fifoASize());
        memory.audio().timerOverflow(1, 1, 0);
        memory.audio().tick(512);
        byte[] samples = memory.audio().drainPcm(1);
        assertEquals(1, samples.length);
        assertEquals(0x44, samples[0]);
    }

    @Test
    void timerOverflowIgnoresDisabledDirectSoundOutputs() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write16(GbaAudio.SOUNDCNT_X, 0x0080);
        memory.write32(GbaAudio.FIFO_A, 0x44332211);

        assertEquals(0, memory.audio().timerOverflow(1));
        assertEquals(4, memory.audio().fifoASize());
        assertEquals(0, memory.audio().drainPcm(4).length);
    }

    @Test
    void soundControlHResetBitsClearFifosAndAreNotStored() {
        GbaMemory memory = GbaMemory.withoutBios(new byte[0]);

        memory.write32(GbaAudio.FIFO_A, 0x44332211);
        memory.write32(GbaAudio.FIFO_B, 0x88776655);
        memory.write16(GbaAudio.SOUNDCNT_H, 0x8C00);

        assertEquals(0, memory.audio().fifoASize());
        assertEquals(0, memory.audio().fifoBSize());
        assertEquals(0x0400, memory.read16(GbaAudio.SOUNDCNT_H));
    }
}
