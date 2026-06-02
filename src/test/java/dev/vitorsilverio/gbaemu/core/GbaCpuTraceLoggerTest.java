package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.Condition;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionKind;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbaCpuTraceLoggerTest {
    @Test
    void printsHeadImmediatelyAndFlushesTailAtTheEnd() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GbaCpuTraceLogger trace = new GbaCpuTraceLogger(new PrintStream(bytes, true, StandardCharsets.UTF_8), 2, 2);
        ArmCore core = new ArmCore(GbaMemory.withoutBios(new byte[0]), SwiDispatcher.empty());

        for (int i = 0; i < 5; i++) {
            trace.afterInstruction(core, instruction(0x100 + i * 2));
        }
        trace.flushTail();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("cpu-trace #1"));
        assertTrue(output.contains("cpu-trace #2"));
        assertFalse(output.contains("cpu-trace #3 set="));
        assertTrue(output.contains("cpu-trace tail last 2 of 5 instructions"));
        assertTrue(output.contains("cpu-trace #4"));
        assertTrue(output.contains("cpu-trace #5"));
    }

    private static DecodedInstruction instruction(int address) {
        return new DecodedInstruction(
                address,
                0x2000,
                InstructionSet.THUMB,
                Condition.AL,
                InstructionKind.MOV,
                0,
                0,
                0,
                0,
                false,
                false,
                false);
    }
}
