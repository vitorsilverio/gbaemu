package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.ArmTraceListener;
import dev.vitorsilverio.armjitter.decoder.DecodedInstruction;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;

import java.io.PrintStream;
import java.util.Arrays;

/// Trace pequeno e autocontido para investigar loops de boot da BIOS/ROM.
public final class GbaCpuTraceLogger implements ArmTraceListener {
    private final PrintStream out;
    private final int headLimit;
    private final String[] tailLines;
    private int instructions;
    private int tailIndex;
    private int tailSize;

    public GbaCpuTraceLogger(PrintStream out, int instructionLimit) {
        this(out, instructionLimit, 0);
    }

    public GbaCpuTraceLogger(PrintStream out, int headLimit, int tailLimit) {
        if (headLimit < 0) {
            throw new IllegalArgumentException("headLimit must be >= 0");
        }
        if (tailLimit < 0) {
            throw new IllegalArgumentException("tailLimit must be >= 0");
        }
        this.out = out;
        this.headLimit = headLimit;
        this.tailLines = new String[tailLimit];
    }

    @Override
    public void beforeBlock(ArmCore core, int pc, InstructionSet instructionSet) {
        if (shouldPrintHead()) {
            out.println("cpu-trace block begin set=" + instructionSet
                    + " pc=0x" + hex32(pc)
                    + " cpsr=0x" + hex32(core.cpsr().get()));
        }
    }

    @Override
    public void afterBlock(ArmCore core, int pc, InstructionSet instructionSet, int instructionCount) {
        if (shouldPrintHead()) {
            out.println("cpu-trace block end set=" + instructionSet
                    + " start=0x" + hex32(pc)
                    + " count=" + instructionCount
                    + " next=0x" + hex32(core.programCounter())
                    + " cycles=" + core.cycles());
        }
    }

    @Override
    public void afterInstruction(ArmCore core, DecodedInstruction instruction) {
        instructions++;
        String line = "cpu-trace #" + instructions
                + " set=" + instruction.instructionSet()
                + " pc=0x" + hex32(instruction.address())
                + " raw=0x" + hex(instruction.raw(), instruction.instructionSet() == InstructionSet.THUMB ? 4 : 8)
                + " kind=" + instruction.kind()
                + " next=0x" + hex32(core.programCounter())
                + " r0=0x" + hex32(core.register(0))
                + " r1=0x" + hex32(core.register(1))
                + " r2=0x" + hex32(core.register(2))
                + " r3=0x" + hex32(core.register(3))
                + " r4=0x" + hex32(core.register(4))
                + " r5=0x" + hex32(core.register(5))
                + " r6=0x" + hex32(core.register(6))
                + " r7=0x" + hex32(core.register(7))
                + " r8=0x" + hex32(core.register(8))
                + " r9=0x" + hex32(core.register(9))
                + " r10=0x" + hex32(core.register(10))
                + " r11=0x" + hex32(core.register(11))
                + " r12=0x" + hex32(core.register(12))
                + " sp=0x" + hex32(core.register(13))
                + " lr=0x" + hex32(core.register(14))
                + " cpsr=0x" + hex32(core.cpsr().get())
                + " cycles=" + core.cycles();

        if (instructions <= headLimit) {
            out.println(line);
            if (instructions == headLimit && tailLines.length == 0) {
                core.setTraceListener(ArmTraceListener.none());
                out.println("cpu-trace disabled after " + headLimit + " head instructions");
            }
        } else {
            rememberTail(line);
        }
    }

    public void flushTail() {
        if (tailLines.length == 0 || tailSize == 0) {
            return;
        }
        out.println("cpu-trace tail last " + tailSize + " of " + instructions + " instructions");
        int start = Math.floorMod(tailIndex - tailSize, tailLines.length);
        for (int i = 0; i < tailSize; i++) {
            out.println(tailLines[(start + i) % tailLines.length]);
        }
        Arrays.fill(tailLines, null);
        tailIndex = 0;
        tailSize = 0;
    }

    private boolean shouldPrintHead() {
        return headLimit > 0 && instructions < headLimit;
    }

    private void rememberTail(String line) {
        if (tailLines.length == 0) {
            return;
        }
        tailLines[tailIndex] = line;
        tailIndex = (tailIndex + 1) % tailLines.length;
        if (tailSize < tailLines.length) {
            tailSize++;
        }
    }

    public boolean shouldInstall() {
        return headLimit > 0 || tailLines.length > 0;
    }

    private static String hex32(int value) {
        return hex(value, 8);
    }

    private static String hex(int value, int digits) {
        return String.format("%0" + digits + "X", value);
    }
}
