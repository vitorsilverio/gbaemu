package dev.vitorsilverio.gbaemu;

import dev.vitorsilverio.gbaemu.cartridge.GbaSaveFile;
import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.core.GbaCpuTraceLogger;
import dev.vitorsilverio.gbaemu.bios.GbaBiosSwi;
import dev.vitorsilverio.gbaemu.desktop.GbaDesktopApp;
import dev.vitorsilverio.gbaemu.desktop.GbaEmulator;
import dev.vitorsilverio.gbaemu.video.GbaVideo;
import dev.vitorsilverio.gbaemu.video.GbaVideoFrameStats;
import dev.vitorsilverio.gbaemu.video.PpmFrameWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Entrada de linha de comando para smoke tests de BIOS/ROM.
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options == null) {
            printUsage();
            return;
        }

        // GUI mode: open the windowed app whenever the user is not running a headless
        // batch (no ROM given, or --window requested). The GUI loads ROMs itself and
        // takes its boot/BIOS/audio settings from the Settings dialog, not the CLI.
        if (options.window() || options.rom() == null || options.gdbPort() > 0) {
            new GbaDesktopApp().launch(
                    options.rom() == null ? null : options.rom().toFile(), options.gdbPort());
            return;
        }

        byte[] rom = Files.readAllBytes(options.rom());
        GbaConsole console;
        if (options.bios() == null) {
            console = GbaConsole.fromRom(rom);
        } else if (options.realSwi()) {
            console = GbaConsole.fromBiosAndRomRealSwi(Files.readAllBytes(options.bios()), rom);
        } else if (options.realBios()) {
            console = GbaConsole.fromBiosAndRom(Files.readAllBytes(options.bios()), rom);
        } else {
            console = GbaConsole.fromBiosAndRomHle(Files.readAllBytes(options.bios()), rom);
        }

        GbaSaveFile saveFile = new GbaSaveFile(options.rom(), console.backup());
        if (saveFile.load()) {
            System.out.println("Save carregado de " + saveFile.path());
        } else if (saveFile.isPersistable()) {
            System.out.println("Sem save previo; sera gravado em " + saveFile.path());
        }

        GbaCpuTraceLogger cpuTrace = new GbaCpuTraceLogger(
                System.out,
                options.traceCpuInstructions(),
                options.traceCpuTailInstructions());
        if (cpuTrace.shouldInstall()) {
            console.cpu().setTraceListener(cpuTrace);
        }

        if (options.steps() > 0) {
            console.stepCpu(options.steps());
        }
        if (options.blocks() > 0) {
            console.runBlocks(options.blocks());
        }
        if (options.cycles() > 0) {
            console.runCycles(options.cycles());
        }
        if (options.postSteps() > 0) {
            console.stepCpu(options.postSteps());
        }
        cpuTrace.flushTail();

        if (options.frameCount() > 1) {
            writeFrameSequence(console, options);
            flushSave(saveFile);
            return;
        }

        int[] renderedFrame = console.renderFrame();
        if (options.frame() != null) {
            PpmFrameWriter.write(options.frame(), renderedFrame, GbaVideo.WIDTH, GbaVideo.HEIGHT);
            System.out.println("Frame escrito em " + options.frame().toAbsolutePath());
        }
        System.out.println("PC=0x" + Integer.toHexString(console.cpu().programCounter()));
        if (options.debugVideo()) {
            GbaVideoFrameStats stats = console.videoFrameStats(renderedFrame);
            System.out.println("video: " + stats.compactSummary());
        }
        if (options.debugState()) {
            printDebugState(console);
        }
        if (options.debugSwi()) {
            printSwiCounts();
        }
        flushSave(saveFile);
    }

    /// Writes the cartridge save to disk if it changed, logging the outcome. Swallows
    /// I/O errors so a transient disk problem never crashes the emulator.
    private static void flushSave(GbaSaveFile saveFile) {
        try {
            if (saveFile.flush()) {
                System.out.println("Save gravado em " + saveFile.path());
            }
        } catch (IOException exception) {
            System.err.println("Falha ao gravar o save em " + saveFile.path() + ": " + exception.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println("""
                Uso:
                  java dev.vitorsilverio.gbaemu.Main --rom game.gba [--bios gba_bios.bin] [--real-bios] [--steps N] [--blocks N] [--cycles N] [--post-steps N] [--frame out.ppm] [--window] [--mute] [--scale N] [--cycles-per-frame N] [--steps-per-frame N] [--debug-video] [--debug-state] [--debug-swi] [--trace-cpu N] [--trace-cpu-tail N]

                Exemplos:
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --steps 1000 --frame boot.ppm"
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --real-bios --steps 1000 --frame boot.ppm"
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --window --scale 3 --steps-per-frame 1000"
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--rom game.gba --blocks 1 --frame frame.ppm"
                """);
    }

    private static void writeFrameSequence(GbaConsole console, CliOptions options) throws Exception {
        Path frame = options.frame();
        Path parent = frame.toAbsolutePath().getParent();
        String fileName = frame.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot >= 0 ? fileName.substring(dot) : ".ppm";

        for (int index = 0; index < options.frameCount(); index++) {
            if (index > 0 && options.frameStepCycles() > 0) {
                console.runCycles(options.frameStepCycles());
            } else if (index > 0 && options.frameStep() > 0) {
                console.stepCpu(options.frameStep());
            }
            int[] renderedFrame = console.renderFrame();
            Path output = parent.resolve(stem + "_" + String.format("%04d", index) + extension);
            PpmFrameWriter.write(output, renderedFrame, GbaVideo.WIDTH, GbaVideo.HEIGHT);
            if (options.debugVideo()) {
                GbaVideoFrameStats stats = console.videoFrameStats(renderedFrame);
                System.out.println("frame " + index + ": " + output + " video: " + stats.compactSummary());
            } else {
                System.out.println("Frame " + index + " escrito em " + output);
            }
        }
        System.out.println("PC=0x" + Integer.toHexString(console.cpu().programCounter()));
        if (options.debugState()) {
            printDebugState(console);
        }
        if (options.debugSwi()) {
            printSwiCounts();
        }
    }

    private static void printDebugState(GbaConsole console) {
        System.out.println("saveType=" + console.cartridge().saveType());
        printIo16(console, "DISPCNT", 0x04000000);
        printIo16(console, "DISPSTAT", 0x04000004);
        printIo16(console, "VCOUNT", 0x04000006);
        for (int bg = 0; bg < 4; bg++) {
            printIo16(console, "BG" + bg + "CNT", 0x04000008 + bg * 2);
        }
        printIo16(console, "WIN0H", 0x04000040);
        printIo16(console, "WIN1H", 0x04000042);
        printIo16(console, "WIN0V", 0x04000044);
        printIo16(console, "WIN1V", 0x04000046);
        printIo16(console, "WININ", 0x04000048);
        printIo16(console, "WINOUT", 0x0400004A);
        printIo16(console, "BLDCNT", 0x04000050);
        printIo16(console, "BLDALPHA", 0x04000052);
        printIo16(console, "BLDY", 0x04000054);
        printIo16(console, "IE", 0x04000200);
        printIo16(console, "IF", 0x04000202);
        printIo16(console, "IME", 0x04000208);
        printIo16(console, "KEYINPUT", 0x04000130);
        for (int timer = 0; timer < 4; timer++) {
            int base = 0x04000100 + timer * 4;
            System.out.printf("TM%dCNT_L=0x%04X TM%dCNT_H=0x%04X%n",
                    timer,
                    console.bus().read16(base),
                    timer,
                    console.bus().read16(base + 2));
        }
        for (int dma = 0; dma < 4; dma++) {
            int base = 0x040000B0 + dma * 12;
            System.out.printf("DMA%dSAD=0x%08X DMA%dDAD=0x%08X DMA%dCNT_L=0x%04X DMA%dCNT_H=0x%04X%n",
                    dma,
                    console.bus().read32(base),
                    dma,
                    console.bus().read32(base + 4),
                    dma,
                    console.bus().read16(base + 8),
                    dma,
                    console.bus().read16(base + 10));
        }
        printVisibleObjects(console, 16);
        printMemoryWords(console, 0x02000000, 16);
        printMemoryWords(console, 0x02020000, 16);
        printHeapChain(console, 0x02020008, 24);
        printMemoryWords(console, 0x03007FF0, 4);
    }

    private static void printSwiCounts() {
        int[] counts = GbaBiosSwi.callCountsSnapshot();
        System.out.print("swi:");
        for (int swi = 0; swi < counts.length; swi++) {
            if (counts[swi] != 0) {
                System.out.printf(" %02X=%d", swi, counts[swi]);
            }
        }
        System.out.println();
        for (String event : GbaBiosSwi.debugEventsSnapshot()) {
            System.out.println("swi-event: " + event);
        }
    }

    private static void printIo16(GbaConsole console, String name, int address) {
        System.out.printf("%s=0x%04X%n", name, console.bus().read16(address));
    }

    private static void printMemoryWords(GbaConsole console, int address, int words) {
        System.out.printf("mem[0x%08X]:", address);
        for (int i = 0; i < words; i++) {
            System.out.printf(" %08X", console.bus().read32(address + i * 4));
        }
        System.out.println();
    }

    private static void printHeapChain(GbaConsole console, int headAddress, int limit) {
        int node = console.bus().read32(headAddress);
        System.out.printf("heap head[0x%08X]=0x%08X%n", headAddress, node);
        for (int i = 0; i < limit && node >= 0x02000000 && node < 0x02040000; i++) {
            System.out.printf(
                    "heap[%02d] node=0x%08X flags=0x%04X size=0x%08X prev=0x%08X next=0x%08X%n",
                    i,
                    node,
                    console.bus().read16(node),
                    console.bus().read32(node + 4),
                    console.bus().read32(node + 8),
                    console.bus().read32(node + 12));
            node = console.bus().read32(node + 12);
        }
    }

    private static void printVisibleObjects(GbaConsole console, int limit) {
        int printed = 0;
        for (int object = 0; object < 128 && printed < limit; object++) {
            int base = 0x07000000 + object * 8;
            int attr0 = console.bus().read16(base);
            int attr1 = console.bus().read16(base + 2);
            int attr2 = console.bus().read16(base + 4);
            boolean affine = (attr0 & (1 << 8)) != 0;
            boolean disabled = !affine && (attr0 & (1 << 9)) != 0;
            int objectMode = (attr0 >>> 10) & 0x3;
            if (disabled || objectMode == 2) {
                continue;
            }
            int x = attr1 & 0x1FF;
            int y = attr0 & 0xFF;
            if (x >= 256) {
                x -= 512;
            }
            if (y >= 160) {
                y -= 256;
            }
            System.out.printf("OBJ%03d attr0=0x%04X attr1=0x%04X attr2=0x%04X x=%d y=%d%n",
                    object, attr0, attr1, attr2, x, y);
            printed++;
        }
    }

    private record CliOptions(
            Path rom,
            Path bios,
            int steps,
            int blocks,
            long cycles,
            int postSteps,
            Path frame,
            boolean window,
            boolean realBios,
            boolean realSwi,
            int scale,
            int stepsPerFrame,
            int cyclesPerFrame,
            boolean debugVideo,
            boolean debugState,
            boolean debugSwi,
            boolean muteAudio,
            int traceCpuInstructions,
            int traceCpuTailInstructions,
            int frameCount,
            int frameStep,
            int frameStepCycles,
            int gdbPort) {
        private static CliOptions parse(String[] args) {
            Path rom = null;
            Path bios = null;
            Path frame = Path.of("frame.ppm");
            int steps = 0;
            int blocks = 0;
            long cycles = 0;
            int postSteps = 0;
            boolean window = false;
            boolean realBios = false;
            boolean realSwi = false;
            int scale = 3;
            int stepsPerFrame = 0;
            int cyclesPerFrame = GbaEmulator.DEFAULT_CYCLES_PER_FRAME;
            boolean debugVideo = false;
            boolean debugState = false;
            boolean debugSwi = false;
            boolean muteAudio = false;
            int traceCpuInstructions = 0;
            int traceCpuTailInstructions = 0;
            int frameCount = 1;
            int frameStep = 0;
            int frameStepCycles = 0;
            int gdbPort = 0;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--rom" -> rom = Path.of(value(args, ++i, arg));
                    case "--bios" -> bios = Path.of(value(args, ++i, arg));
                    case "--steps" -> steps = Integer.parseInt(value(args, ++i, arg));
                    case "--blocks" -> blocks = Integer.parseInt(value(args, ++i, arg));
                    case "--cycles" -> cycles = Long.parseLong(value(args, ++i, arg));
                    case "--post-steps" -> postSteps = Integer.parseInt(value(args, ++i, arg));
                    case "--frame" -> frame = Path.of(value(args, ++i, arg));
                    case "--no-frame" -> frame = null;
                    case "--window" -> window = true;
                    case "--real-bios" -> realBios = true;
                    case "--real-swi" -> realSwi = true;
                    case "--scale" -> scale = Integer.parseInt(value(args, ++i, arg));
                    case "--steps-per-frame" -> stepsPerFrame = Integer.parseInt(value(args, ++i, arg));
                    case "--cycles-per-frame" -> cyclesPerFrame = Integer.parseInt(value(args, ++i, arg));
                    case "--debug-video" -> debugVideo = true;
                    case "--debug-state" -> debugState = true;
                    case "--debug-swi" -> debugSwi = true;
                    case "--mute", "--no-audio" -> muteAudio = true;
                    case "--trace-cpu" -> traceCpuInstructions = Integer.parseInt(value(args, ++i, arg));
                    case "--trace-cpu-tail" -> traceCpuTailInstructions = Integer.parseInt(value(args, ++i, arg));
                    case "--frame-count" -> frameCount = Integer.parseInt(value(args, ++i, arg));
                    case "--frame-step" -> frameStep = Integer.parseInt(value(args, ++i, arg));
                    case "--frame-step-cycles" -> frameStepCycles = Integer.parseInt(value(args, ++i, arg));
                    case "--gdb" -> gdbPort = 2345;
                    case "--help", "-h" -> {
                        return null;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            // A null ROM is allowed: Main then launches the GUI (browse for a ROM there).
            // Only --help returns null (to print usage).
            return new CliOptions(
                    rom,
                    bios,
                    steps,
                    blocks,
                    cycles,
                    postSteps,
                    frame,
                    window,
                    realBios,
                    realSwi,
                    scale,
                    stepsPerFrame,
                    cyclesPerFrame,
                    debugVideo,
                    debugState,
                    debugSwi,
                    muteAudio,
                    traceCpuInstructions,
                    traceCpuTailInstructions,
                    frameCount,
                    frameStep,
                    frameStepCycles,
                    gdbPort);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
