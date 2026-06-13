package dev.vitorsilverio.gbaemu.core;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;
import dev.vitorsilverio.gbaemu.audio.GbaAudio;
import dev.vitorsilverio.gbaemu.bios.GbaBiosSwi;
import dev.vitorsilverio.gbaemu.cartridge.CartridgeBackup;
import dev.vitorsilverio.gbaemu.cartridge.GbaCartridge;
import dev.vitorsilverio.gbaemu.cartridge.GbaEepromSave;
import dev.vitorsilverio.gbaemu.cartridge.GbaRom;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveMemory;
import dev.vitorsilverio.gbaemu.cartridge.GbaSaveType;
import dev.vitorsilverio.gbaemu.dma.GbaDmaController;
import dev.vitorsilverio.gbaemu.input.GbaKeypad;
import dev.vitorsilverio.gbaemu.interrupt.GbaInterruptController;
import dev.vitorsilverio.gbaemu.memory.GbaBios;
import dev.vitorsilverio.gbaemu.memory.GbaBus;
import dev.vitorsilverio.gbaemu.memory.GbaEwram;
import dev.vitorsilverio.gbaemu.memory.GbaIwram;
import dev.vitorsilverio.gbaemu.serial.GbaSerial;
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
    private final GbaSerial serial;

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
            GbaAudio audio,
            GbaSerial serial) {
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
        this.serial = Objects.requireNonNull(serial, "serial");
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

    /// Skip-BIOS boot but with the HLE SWI handler DISABLED, so every SWI traps to the
    /// real BIOS code at 0x08 (needs a real BIOS). Diagnostic A/B test: if a glitch
    /// disappears here, our HLE SWI (e.g. a decompression routine) is the culprit.
    public static GbaConsole fromBiosAndRomRealSwi(byte[] bios, byte[] rom) {
        GbaCartridge cartridge = GbaCartridge.load(rom);
        GbaConsole console = create(bios, cartridge.rom(), cartridge, ROM_ENTRY_POINT, true);
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

    /// Backing store for the cartridge save chip (SRAM/Flash), used to persist the
    /// in-memory save to a `.sav` file. Always present (size 0 when the ROM has no save).
    public GbaSaveMemory saveMemory() {
        return bus.find(GbaSaveMemory.class)
                .orElseThrow(() -> new IllegalStateException("save memory not registered on the bus"));
    }

    /// The active cartridge backup to persist: the serial EEPROM when the cartridge uses one,
    /// otherwise the memory-mapped SRAM/Flash store.
    public CartridgeBackup backup() {
        return bus.find(GbaEepromSave.class).map(e -> (CartridgeBackup) e).orElseGet(this::saveMemory);
    }

    public GbaSystemControl systemControl() { return systemControl; }

    public GbaSerial serial() { return serial; }

    private static final int SAVE_STATE_MAGIC = 0x47424153; // "GBAS"
    // v2 added the serial peripheral block; older states are rejected rather than misread.
    private static final int SAVE_STATE_VERSION = 2;

    /// Writes a full machine snapshot — CPU, all writable RAM (EWRAM/IWRAM/palette/VRAM/OAM),
    /// every I/O peripheral and the cartridge save — to `path`. The BIOS and ROM are static
    /// (reloaded from their files), so they are not stored.
    public void saveState(java.nio.file.Path path) throws java.io.IOException {
        try (var out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(path)))) {
            out.writeInt(SAVE_STATE_MAGIC);
            out.writeInt(SAVE_STATE_VERSION);
            cpu.saveState(out);
            snapshotRam(out, 0x02000000, 0x40000); // EWRAM 256 KB
            snapshotRam(out, 0x03000000, 0x08000); // IWRAM 32 KB
            snapshotRam(out, 0x05000000, 0x00400); // palette 1 KB
            snapshotRam(out, 0x06000000, 0x18000); // VRAM 96 KB
            snapshotRam(out, 0x07000000, 0x00400); // OAM 1 KB
            interrupts.saveState(out);
            systemControl.saveState(out);
            timers.saveState(out);
            dma.saveState(out);
            lcdTiming.saveState(out);
            audio.saveState(out);
            keypad.saveState(out);
            serial.saveState(out);
            byte[] cartridgeSave = backup().snapshot();
            out.writeInt(cartridgeSave.length);
            out.write(cartridgeSave);
        }
    }

    /// Restores a snapshot written by {@link #saveState}, then drops every compiled JIT block
    /// so recompilation reflects the restored memory (essential for self-modifying games).
    public void loadState(java.nio.file.Path path) throws java.io.IOException {
        try (var in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(path)))) {
            if (in.readInt() != SAVE_STATE_MAGIC) {
                throw new java.io.IOException("not a gbaemu save state");
            }
            int version = in.readInt();
            if (version != SAVE_STATE_VERSION) {
                throw new java.io.IOException("unsupported save state version: " + version);
            }
            cpu.loadState(in);
            restoreRam(in, 0x02000000, 0x40000);
            restoreRam(in, 0x03000000, 0x08000);
            restoreRam(in, 0x05000000, 0x00400);
            restoreRam(in, 0x06000000, 0x18000);
            restoreRam(in, 0x07000000, 0x00400);
            interrupts.loadState(in);
            systemControl.loadState(in);
            timers.loadState(in);
            dma.loadState(in);
            lcdTiming.loadState(in);
            audio.loadState(in);
            keypad.loadState(in);
            serial.loadState(in);
            backup().load(in.readNBytes(in.readInt()));
            runtime.blockCache().clear();
            updateInterruptLine();
        }
    }

    private void snapshotRam(java.io.DataOutputStream out, int base, int length) throws java.io.IOException {
        for (int offset = 0; offset < length; offset += 4) {
            out.writeInt(bus.read32(base + offset));
        }
    }

    private void restoreRam(java.io.DataInputStream in, int base, int length) throws java.io.IOException {
        for (int offset = 0; offset < length; offset += 4) {
            bus.write32(base + offset, in.readInt());
        }
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

    /// Enables (or disables) drawing each visible scanline during emulation, as its HBlank
    /// is reached, so per-scanline register changes (affine/scroll/priority/window) render
    /// correctly. Off by default; the interactive window turns it on and reads the result
    /// via {@link #currentFrame()}. Headless callers keep using {@link #renderFrame()}.
    public void setScanlineRenderingEnabled(boolean enabled) {
        lcdTiming.setScanlineRenderer(enabled ? line -> video.renderScanline(bus, line) : null);
    }

    /// The last fully-rendered frame, published once per frame at VBlank (see
    /// {@link #setScanlineRenderingEnabled}). Never torn mid-render; does not re-render.
    public int[] currentFrame() {
        return video.presentedFrame();
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
        return create(biosBytes, rom, cartridge, entryPoint, false);
    }

    private static GbaConsole create(byte[] biosBytes, byte[] rom, GbaCartridge cartridge, int entryPoint, boolean realBiosSwi) {
        // Peripherals (constructed before bus so bus can reference them)
        GbaInterruptController interrupts  = new GbaInterruptController();
        GbaLcdTiming lcdTiming             = new GbaLcdTiming(interrupts);
        GbaAudio audio                     = new GbaAudio();
        GbaTimerController timers          = new GbaTimerController(interrupts);
        GbaKeypad keypad                   = new GbaKeypad(interrupts);
        GbaSystemControl systemControl     = new GbaSystemControl();
        GbaSerial serial                   = new GbaSerial(interrupts);
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
        bus.add(serial);            // 0x04000120-0x0400012F, 0x04000134  (SIO + RCNT)
        bus.add(interrupts);        // 0x04000200-0x04000209  (IE/IF/IME)
        bus.add(systemControl);     // 0x04000204, 0x04000300-0x04000301
        bus.add(new GbaEwram());    // 0x02000000-0x02FFFFFF
        bus.add(new GbaIwram());    // 0x03000000-0x03FFFFFF
        bus.add(new GbaVideoMemory(() -> lcdTiming.readByte(0x04000000) & 0x7)); // 0x05-0x07xxxxxx
        if (cartridge.saveType() == GbaSaveType.EEPROM) {
            // EEPROM answers on the 0x0D block (mirrored across it for <=16 MB ROMs); it must be
            // resolved before the ROM, which nominally claims 0x08-0x0D.
            bus.add(new GbaEepromSave());
        }
        bus.add(gbRom);             // 0x08000000-0x0DFFFFFF
        bus.add(saveMemory);        // 0x0E000000-0x0FFFFFFF

        if (biosBytes != null) {
            bus.add(new GbaBios(biosBytes)); // 0x00000000-0x00003FFF
        } else {
            bus.add(new GbaBios(skipBiosStub()));
        }

        SwiDispatcher swiDispatcher = realBiosSwi
                ? SwiDispatcher.empty()
                : GbaBiosSwi.dispatcher(bus, systemControl);
        // The CPU accesses memory through an invalidation-aware decorator so that writes to code
        // regions (e.g. a game building a routine on the stack/IWRAM, like Mario Kart) drop any
        // stale JIT block cached for that address. The DMA/peripherals keep the raw bus.
        JitRuntime runtime = createRuntime();
        ArmCore cpu = createCpu(new InvalidationAwareAddressSpace(bus, runtime), swiDispatcher, entryPoint);
        return new GbaConsole(bus, cpu, runtime, new GbaVideo(),
                lcdTiming, dma, interrupts, timers, keypad, cartridge, systemControl, audio, serial);
    }

    private static ArmCore createCpu(AddressSpace bus, SwiDispatcher swiDispatcher, int entryPoint) {
        // The GBA CPU is an ARM7TDMI (ARMv4T); be explicit so NDS/ARMv5 rules never leak in.
        ArmCore cpu = new ArmCore(bus, swiDispatcher, ArmArchitecture.ARMV4T);
        cpu.configureExecutionState(entryPoint, CpuMode.SUPERVISOR, InstructionSet.ARM, true, true);
        cpu.setRegister(13, SUPERVISOR_STACK_POINTER);
        return cpu;
    }

    private static JitRuntime createRuntime() {
        return JitRuntimeFactory.interpretedArmThumb(16 * 1024, 1, ArmArchitecture.ARMV4T);
    }

    private void updateInterruptLine() {
        cpu.setInterruptLine(interrupts.pending());
    }

    private boolean advanceHalted(int cycles) {
        if (!systemControl.halted()) return false;
        tickHardware(cycles);
        // An IntrWait/VBlankIntrWait returns to the game only once an awaited interrupt has
        // actually fired; record that here (independent of IME). The CPU still wakes on any
        // pending interrupt so unrelated handlers (e.g. a VCount raster effect) run mid-frame
        // — the re-executed SWI then re-halts until the awaited interrupt arrives.
        int waitMask = systemControl.intrWaitMask();
        boolean awaitedFired = waitMask != 0 && interrupts.requestedWithin(waitMask);
        if (awaitedFired) {
            systemControl.markIntrWaitSatisfied();
        }
        if (interrupts.pending() || awaitedFired) {
            systemControl.resume();
            updateInterruptLine();
        }
        return true;
    }

    private void tickHardware(int cycles) {
        serial.tick(cycles);
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
        // Mirror the real BIOS IRQ path at its canonical addresses. The vector at 0x18 branches
        // to the handler at 0x128, which saves r0-r3/r12/lr, sets lr = 0x138, and jumps to the
        // user IRQ handler at [0x03007FFC]; that handler returns (via lr, or by jumping straight
        // to 0x138 as many AGB games do — e.g. Metroid Fusion) to the epilogue at 0x138, which
        // restores and returns from the IRQ. Without code at 0x138 those games crash into zeros.
        write32(stub, 0x18, 0xEA000042);  // B 0x128
        write32(stub, 0x128, 0xE92D500F); // STMFD SP!, {r0-r3, r12, lr}
        write32(stub, 0x12C, 0xE59F0010); // LDR  R0, [PC, #0x10]   ; R0 = &userIrqHandler (0x03007FFC)
        write32(stub, 0x130, 0xE28FE000); // ADD  LR, PC, #0        ; LR = 0x138 (epilogue)
        write32(stub, 0x134, 0xE590F000); // LDR  PC, [R0]          ; jump to the user IRQ handler
        write32(stub, 0x138, 0xE8BD500F); // LDMFD SP!, {r0-r3, r12, lr}
        write32(stub, 0x13C, 0xE25EF004); // SUBS PC, LR, #4        ; return from IRQ
        write32(stub, 0x144, 0x03007FFC); // literal: address of the user IRQ handler pointer
        return stub;
    }

    private static void write32(byte[] target, int offset, int value) {
        target[offset]     = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }
}
