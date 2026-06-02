package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.bios.GbaBiosSwi;
import dev.vitorsilverio.gbaemu.cartridge.GbaCartridge;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveMemory;
import dev.vitorsilverio.gbaemu.dma.GbaDmaController;
import dev.vitorsilverio.gbaemu.input.GbaKeypad;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaMemory;
import dev.vitorsilverio.gbaemu.system.GbaSystemControl;
import dev.vitorsilverio.gbaemu.timer.GbaTimerController;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming.Events;
import dev.vitorsilverio.gbaemu.video.GbaVideo;
import dev.vitorsilverio.gbaemu.video.GbaVideoFrameStats;

import java.util.Objects;

/// Fachada minima do console GBA ligando memoria, CPU ARM7TDMI e runtime ARM/THUMB.
public final class GbaConsole {
    public static final int ROM_ENTRY_POINT = 0x08000000;
    public static final int BIOS_ENTRY_POINT = 0x00000000;
    public static final int USER_STACK_POINTER = 0x03007F00;
    public static final int IRQ_STACK_POINTER = 0x03007FA0;
    public static final int SUPERVISOR_STACK_POINTER = 0x03007FE0;
    private static final int HARDWARE_STEP_BATCH = 8;
    private static final int HALT_TICK_BATCH = 64;

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
    private final GbaSystemControl systemControl;

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
            GbaCartridge cartridge,
            GbaSystemControl systemControl) {
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
        this.systemControl = Objects.requireNonNull(systemControl, "systemControl");
    }

    public static GbaConsole fromRom(byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaMemory memory = GbaMemory.withoutBios(cartridge.rom(), GbaSaveMemory.forType(cartridge.saveType()));
        GbaSystemControl systemControl = new GbaSystemControl(memory);
        ArmCore cpu = createBiosCpu(memory, systemControl, ROM_ENTRY_POINT);
        GbaConsole console = create(memory, cpu, cartridge, systemControl);
        console.applySkipBiosState();
        return console;
    }

    public static GbaConsole fromBiosAndRom(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaMemory memory = GbaMemory.withBios(bios, cartridge.rom(), GbaSaveMemory.forType(cartridge.saveType()));
        GbaSystemControl systemControl = new GbaSystemControl(memory);
        ArmCore cpu = createBiosCpu(memory, systemControl, BIOS_ENTRY_POINT);
        return create(memory, cpu, cartridge, systemControl);
    }

    public static GbaConsole fromBiosAndRomHle(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaMemory memory = GbaMemory.withBiosHle(bios, cartridge.rom(), GbaSaveMemory.forType(cartridge.saveType()));
        GbaSystemControl systemControl = new GbaSystemControl(memory);
        ArmCore cpu = createBiosCpu(memory, systemControl, ROM_ENTRY_POINT);
        GbaConsole console = create(memory, cpu, cartridge, systemControl);
        console.applySkipBiosState();
        return console;
    }

    public GbaMemory memory() {
        return memory;
    }

    public GbaAudio audio() {
        return memory.audio();
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

    public GbaSystemControl systemControl() {
        return systemControl;
    }

    public long runBlocks(int blockCount) {
        long consumed = 0;
        for (int i = 0; i < blockCount; i++) {
            if (advanceHalted(HALT_TICK_BATCH)) {
                consumed += HALT_TICK_BATCH;
                continue;
            }
            long before = cpu.cycles();
            cpu.runBlock(runtime);
            int cycles = Math.toIntExact(cpu.cycles() - before);
            consumed += cycles;
            tickHardware(cycles);
        }
        return consumed;
    }

    public long runCycles(long cycleBudget) {
        if (cycleBudget < 0) {
            throw new IllegalArgumentException("cycleBudget must be >= 0");
        }
        long consumed = 0;
        while (consumed < cycleBudget) {
            if (advanceHalted(Math.toIntExact(Math.min(HALT_TICK_BATCH, cycleBudget - consumed)))) {
                consumed += Math.min(HALT_TICK_BATCH, cycleBudget - consumed);
                continue;
            }
            long before = cpu.cycles();
            cpu.runBlock(runtime);
            int cycles = Math.toIntExact(cpu.cycles() - before);
            if (cycles <= 0) {
                break;
            }
            consumed += cycles;
            tickHardware(cycles);
        }
        return consumed;
    }

    public int stepCpu(int instructionCount) {
        int steps = 0;
        while (steps < instructionCount) {
            if (advanceHalted(HALT_TICK_BATCH)) {
                steps++;
                continue;
            }
            int requested = Math.min(HARDWARE_STEP_BATCH, instructionCount - steps);
            long before = cpu.cycles();
            int executed = cpu.step(requested);
            int cycles = Math.toIntExact(cpu.cycles() - before);
            steps += executed;
            tickHardware(cycles);
            if (executed == 0) {
                break;
            }
        }
        return steps;
    }

    public int[] renderFrame() {
        return video.renderFrame(memory);
    }

    public GbaVideoFrameStats videoFrameStats(int[] frame) {
        return GbaVideoFrameStats.capture(memory, frame);
    }

    public void applySkipBiosState() {
        cpu.configureExecutionState(
                ROM_ENTRY_POINT,
                CpuMode.SYSTEM,
                InstructionSet.ARM,
                true,
                true);
        cpu.setRegister(13, USER_STACK_POINTER);
        cpu.setRegister(14, 0);
        systemControl.setPostBootFlag(true);
    }

    private static ArmCore createBiosCpu(GbaMemory memory, GbaSystemControl systemControl, int entryPoint) {
        ArmCore cpu = new ArmCore(memory, GbaBiosSwi.dispatcher(memory, systemControl));
        cpu.configureExecutionState(
                entryPoint,
                CpuMode.SUPERVISOR,
                InstructionSet.ARM,
                true,
                true);
        cpu.setRegister(13, SUPERVISOR_STACK_POINTER);
        return cpu;
    }

    private static JitRuntime createRuntime() {
        return JitRuntimeFactory.interpretedArmThumb(16 * 1024, 1);
    }

    private static GbaConsole create(
            GbaMemory memory,
            ArmCore cpu,
            GbaCartridge cartridge,
            GbaSystemControl systemControl) {
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
                cartridge,
                systemControl);
    }

    private void updateInterruptLine() {
        cpu.setInterruptLine(interrupts.pending());
    }

    private boolean advanceHalted(int cycles) {
        if (!systemControl.halted()) {
            return false;
        }
        tickHardware(cycles);
        if (interrupts.pending()) {
            systemControl.resume();
            updateInterruptLine();
        }
        return true;
    }

    private void tickHardware(int cycles) {
        dma.triggerImmediateTransfers();
        int timerOverflows = timers.tick(cycles);
        dma.triggerAudioFifoTransfers(audio().timerOverflow(
                timerOverflows,
                timers.overflowCount(0),
                timers.overflowCount(1)));
        audio().tick(cycles);
        triggerTimedDma(lcdTiming.tick(cycles));
        updateInterruptLine();
    }

    private void triggerTimedDma(Events events) {
        for (int i = 0; i < events.vblankStartedCount(); i++) {
            dma.triggerVblankTransfers();
        }
        for (int i = 0; i < events.hblankStartedCount(); i++) {
            dma.triggerHblankTransfers();
        }
    }
}
