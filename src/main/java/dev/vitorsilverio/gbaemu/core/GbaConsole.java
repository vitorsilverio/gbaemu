package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpsrRegister;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.cartridge.GbaCartridge;
import dev.vitorsilverio.gbaemu.dma.GbaDmaController;
import dev.vitorsilverio.gbaemu.input.GbaKeypad;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import dev.vitorsilverio.gbaemu.timer.GbaTimerController;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import dev.vitorsilverio.gbaemu.video.GbaVideo;

import java.util.Objects;

/// Fachada minima do console GBA ligando memoria, CPU ARM7TDMI e runtime ARM/THUMB.
public final class GbaConsole {
    public static final int ROM_ENTRY_POINT = 0x08000000;
    public static final int BIOS_ENTRY_POINT = 0x00000000;

    private final GbaMemory memory;
    private final ArmCore cpu;
    private final JitRuntime runtime;
    private final GbaVideo video;
    private final GbaLcdTiming lcdTiming;
    private final GbaDmaController dma;
    private final GbaInterruptController interrupts;
    private final GbaTimerController timers;
    private final GbaKeypad keypad;
    private final GbaCartridge cartridge;

    private GbaConsole(
            GbaMemory memory,
            ArmCore cpu,
            JitRuntime runtime,
            GbaVideo video,
            GbaLcdTiming lcdTiming,
            GbaDmaController dma,
            GbaInterruptController interrupts,
            GbaTimerController timers,
            GbaKeypad keypad,
            GbaCartridge cartridge) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.cpu = Objects.requireNonNull(cpu, "cpu");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.video = Objects.requireNonNull(video, "video");
        this.lcdTiming = Objects.requireNonNull(lcdTiming, "lcdTiming");
        this.dma = Objects.requireNonNull(dma, "dma");
        this.interrupts = Objects.requireNonNull(interrupts, "interrupts");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.keypad = Objects.requireNonNull(keypad, "keypad");
        this.cartridge = Objects.requireNonNull(cartridge, "cartridge");
    }

    public static GbaConsole fromRom(byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaMemory memory = GbaMemory.withoutBios(cartridge.rom());
        ArmCore cpu = createBootCpu(memory, ROM_ENTRY_POINT);
        return create(memory, cpu, cartridge);
    }

    public static GbaConsole fromBiosAndRom(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaMemory memory = GbaMemory.withBios(bios, cartridge.rom());
        ArmCore cpu = createBootCpu(memory, BIOS_ENTRY_POINT);
        return create(memory, cpu, cartridge);
    }

    public GbaMemory memory() {
        return memory;
    }

    public ArmCore cpu() {
        return cpu;
    }

    public JitRuntime runtime() {
        return runtime;
    }

    public GbaVideo video() {
        return video;
    }

    public GbaLcdTiming lcdTiming() {
        return lcdTiming;
    }

    public GbaDmaController dma() {
        return dma;
    }

    public GbaInterruptController interrupts() {
        return interrupts;
    }

    public GbaTimerController timers() {
        return timers;
    }

    public GbaKeypad keypad() {
        return keypad;
    }

    public GbaCartridge cartridge() {
        return cartridge;
    }

    public long runBlocks(int blockCount) {
        long cycles = cpu.runBlocks(runtime, blockCount);
        dma.triggerImmediateTransfers();
        timers.tick(Math.toIntExact(cycles));
        lcdTiming.tick(Math.toIntExact(cycles));
        updateInterruptLine();
        return cycles;
    }

    public int stepCpu(int instructionCount) {
        long before = cpu.cycles();
        int steps = cpu.step(instructionCount);
        int cycles = Math.toIntExact(cpu.cycles() - before);
        dma.triggerImmediateTransfers();
        timers.tick(cycles);
        lcdTiming.tick(cycles);
        updateInterruptLine();
        return steps;
    }

    public int[] renderFrame() {
        return video.renderFrame(memory);
    }

    private static ArmCore createBootCpu(GbaMemory memory, int entryPoint) {
        ArmCore cpu = new ArmCore(memory, SwiDispatcher.empty());
        cpu.setProgramCounter(entryPoint);
        cpu.cpsr().set(CpuMode.SUPERVISOR.bits()
                | CpsrRegister.IRQ_DISABLE_FLAG
                | CpsrRegister.FIQ_DISABLE_FLAG);
        return cpu;
    }

    private static JitRuntime createRuntime() {
        return JitRuntimeFactory.interpretedArmThumb(1024, 2);
    }

    private static GbaConsole create(GbaMemory memory, ArmCore cpu, GbaCartridge cartridge) {
        GbaInterruptController interrupts = new GbaInterruptController(memory);
        return new GbaConsole(
                memory,
                cpu,
                createRuntime(),
                new GbaVideo(),
                new GbaLcdTiming(memory, interrupts),
                new GbaDmaController(memory, interrupts),
                interrupts,
                new GbaTimerController(memory, interrupts),
                new GbaKeypad(memory, interrupts),
                cartridge);
    }

    private void updateInterruptLine() {
        cpu.setInterruptLine(interrupts.pending());
    }
}
