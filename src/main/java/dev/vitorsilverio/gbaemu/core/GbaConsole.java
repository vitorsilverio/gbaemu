package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.bios.GbaBiosSwi;
import dev.vitorsilverio.gbaemu.cartridge.GbaCartridge;
import dev.vitorsilverio.gbaemu.cartridge.GbaRom;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveMemory;
import dev.vitorsilverio.gbaemu.dma.GbaDmaController;
import dev.vitorsilverio.gbaemu.input.GbaKeypad;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaBios;
import dev.vitorsilverio.gbaemu.memory.GbaBus;
import dev.vitorsilverio.gbaemu.memory.GbaEwram;
import dev.vitorsilverio.gbaemu.memory.GbaIwram;
import dev.vitorsilverio.gbaemu.system.GbaSystemControl;
import dev.vitorsilverio.gbaemu.timer.GbaTimerController;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming;
import dev.vitorsilverio.gbaemu.video.GbaLcdTiming.Events;
import dev.vitorsilverio.gbaemu.video.GbaVideo;
import dev.vitorsilverio.gbaemu.video.GbaVideoMemory;
import dev.vitorsilverio.gbaemu.video.GbaVideoFrameStats;

import java.util.Objects;

/// Fachada minima do console GBA ligando bus, CPU ARM7TDMI e perifericos.
public final class GbaConsole {
    public static final int ROM_ENTRY_POINT        = 0x08000000;
    public static final int BIOS_ENTRY_POINT       = 0x00000000;
    public static final int USER_STACK_POINTER      = 0x03007F00;
    public static final int IRQ_STACK_POINTER       = 0x03007FA0;
    public static final int SUPERVISOR_STACK_POINTER = 0x03007FE0;
    private static final int HARDWARE_STEP_BATCH = 8;
    private static final int HALT_TICK_BATCH     = 64;

    private final GbaBus bus;
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
    private final GbaAudio audio;

    private GbaConsole(
            GbaBus bus,
            ArmCore cpu,
            JitRuntime runtime,
            GbaVideo video,
            GbaLcdTiming lcdTiming,
            GbaDmaController dma,
            GbaInterruptController interrupts,
            GbaTimerController timers,
            GbaKeypad keypad,
            GbaCartridge cartridge,
            GbaSystemControl systemControl,
            GbaAudio audio) {
        this.bus = Objects.requireNonNull(bus, "bus");
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
        this.audio = Objects.requireNonNull(audio, "audio");
    }

    public static GbaConsole fromRom(byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaConsole console = create(null, cartridge.rom(), cartridge, ROM_ENTRY_POINT);
        console.applySkipBiosState();
        return console;
    }

    public static GbaConsole fromBiosAndRom(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        return create(bios, cartridge.rom(), cartridge, BIOS_ENTRY_POINT);
    }

    public static GbaConsole fromBiosAndRomHle(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaConsole console = create(bios, cartridge.rom(), cartridge, ROM_ENTRY_POINT);
        console.applySkipBiosState();
        return console;
    }

    public GbaBus bus() { return bus; }

    public GbaAudio audio() { return audio; }

    public ArmCore cpu() { return cpu; }

    public JitRuntime runtime() { return runtime; }

    public GbaVideo video() { return video; }

    public GbaLcdTiming lcdTiming() { return lcdTiming; }

    public GbaDmaController dma() { return dma; }

    public GbaInterruptController interrupts() { return interrupts; }

    public GbaTimerController timers() { return timers; }

    public GbaKeypad keypad() { return keypad; }

    public GbaCartridge cartridge() { return cartridge; }

    public GbaSystemControl systemControl() { return systemControl; }

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
        if (cycleBudget < 0) throw new IllegalArgumentException("cycleBudget must be >= 0");
        long consumed = 0;
        while (consumed < cycleBudget) {
            if (advanceHalted(Math.toIntExact(Math.min(HALT_TICK_BATCH, cycleBudget - consumed)))) {
                consumed += Math.min(HALT_TICK_BATCH, cycleBudget - consumed);
                continue;
            }
            long before = cpu.cycles();
            cpu.runBlock(runtime);
            int cycles = Math.toIntExact(cpu.cycles() - before);
            if (cycles <= 0) break;
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
            if (executed == 0) break;
        }
        return steps;
    }

    public int[] renderFrame() {
        return video.renderFrame(bus);
    }

    public GbaVideoFrameStats videoFrameStats(int[] frame) {
        return GbaVideoFrameStats.capture(bus, frame);
    }

    public void applySkipBiosState() {
        cpu.configureExecutionState(ROM_ENTRY_POINT, CpuMode.SYSTEM, InstructionSet.ARM, false, true);
        cpu.setRegister(13, USER_STACK_POINTER);
        cpu.setRegister(14, 0);
        cpu.setBankedRegister(CpuMode.IRQ, 13, IRQ_STACK_POINTER);
        systemControl.setPostBootFlag(true);
    }

    private static GbaConsole create(byte[] biosBytes, byte[] rom, GbaCartridge cartridge, int entryPoint) {
        // Peripherals (constructed before bus so bus can reference them)
        GbaInterruptController interrupts  = new GbaInterruptController();
        GbaLcdTiming lcdTiming             = new GbaLcdTiming(interrupts);
        GbaAudio audio                     = new GbaAudio();
        GbaTimerController timers          = new GbaTimerController(interrupts);
        GbaKeypad keypad                   = new GbaKeypad(interrupts);
        GbaSystemControl systemControl     = new GbaSystemControl();
        GbaSaveMemory saveMemory           = GbaSaveMemory.forType(cartridge.saveType());
        GbaRom gbRom                       = new GbaRom(rom);

        // Build bus — DMA needs the bus for transfers, so we wire after construction
        GbaBus bus = new GbaBus();
        GbaDmaController dma = new GbaDmaController(bus, interrupts);

        // Memory regions (registered in priority order)
        bus.add(lcdTiming);         // 0x04000000-0x0400005F  (LCD registers)
        bus.add(audio);             // 0x04000060-0x040000A7  (Sound registers)
        bus.add(dma);               // 0x040000B0-0x040000DF  (DMA registers)
        bus.add(timers);            // 0x04000100-0x0400010F  (Timer registers)
        bus.add(keypad);            // 0x04000130-0x04000133  (Keypad registers)
        bus.add(interrupts);        // 0x04000200-0x04000209  (IE/IF/IME)
        bus.add(systemControl);     // 0x04000204, 0x04000300-0x04000301
        bus.add(new GbaEwram());    // 0x02000000-0x02FFFFFF
        bus.add(new GbaIwram());    // 0x03000000-0x03FFFFFF
        bus.add(new GbaVideoMemory(() -> lcdTiming.readByte(0x04000000) & 0x7)); // 0x05-0x07xxxxxx
        bus.add(gbRom);             // 0x08000000-0x0DFFFFFF
        bus.add(saveMemory);        // 0x0E000000-0x0FFFFFFF

        if (biosBytes != null) {
            bus.add(new GbaBios(biosBytes)); // 0x00000000-0x00003FFF
        } else {
            bus.add(new GbaBios(skipBiosStub()));
        }

        ArmCore cpu = createCpu(bus, systemControl, entryPoint);
        return new GbaConsole(bus, cpu, createRuntime(), new GbaVideo(),
                lcdTiming, dma, interrupts, timers, keypad, cartridge, systemControl, audio);
    }

    private static ArmCore createCpu(GbaBus bus, GbaSystemControl systemControl, int entryPoint) {
        ArmCore cpu = new ArmCore(bus, GbaBiosSwi.dispatcher(bus, systemControl));
        cpu.configureExecutionState(entryPoint, CpuMode.SUPERVISOR, InstructionSet.ARM, true, true);
        cpu.setRegister(13, SUPERVISOR_STACK_POINTER);
        return cpu;
    }

    private static JitRuntime createRuntime() {
        return JitRuntimeFactory.interpretedArmThumb(16 * 1024, 1);
    }

    private void updateInterruptLine() {
        cpu.setInterruptLine(interrupts.pending());
    }

    private boolean advanceHalted(int cycles) {
        if (!systemControl.halted()) return false;
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
        dma.triggerAudioFifoTransfers(audio.timerOverflow(
                timerOverflows,
                timers.overflowCount(0),
                timers.overflowCount(1)));
        audio.tick(cycles);
        triggerTimedDma(lcdTiming.tick(cycles));
        updateInterruptLine();
    }

    private void triggerTimedDma(Events events) {
        for (int i = 0; i < events.vblankStartedCount(); i++) dma.triggerVblankTransfers();
        for (int i = 0; i < events.hblankStartedCount(); i++) dma.triggerHblankTransfers();
    }

    private static byte[] skipBiosStub() {
        byte[] stub = new byte[GbaBios.SIZE];
        write32(stub, 0x18, 0xE92D500F);
        write32(stub, 0x1C, 0xE59F001C);
        write32(stub, 0x20, 0xE5900000);
        write32(stub, 0x24, 0xE3500000);
        write32(stub, 0x28, 0x0A000001);
        write32(stub, 0x2C, 0xE1A0E00F);
        write32(stub, 0x30, 0xE12FFF10);
        write32(stub, 0x34, 0xE8BD500F);
        write32(stub, 0x38, 0xE25EF004);
        write32(stub, 0x40, 0x03007FFC);
        return stub;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset]     = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
