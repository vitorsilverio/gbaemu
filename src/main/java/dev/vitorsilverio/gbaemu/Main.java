package dev.vitorsilverio.gbaemu;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.video.GbaVideo;
import dev.vitorsilverio.gbaemu.video.PpmFrameWriter;

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

        byte[] rom = Files.readAllBytes(options.rom());
        GbaConsole console;
        if (options.bios() == null) {
            console = GbaConsole.fromRom(rom);
        } else {
            console = GbaConsole.fromBiosAndRom(Files.readAllBytes(options.bios()), rom);
        }

        if (options.steps() > 0) {
            console.stepCpu(options.steps());
        }
        if (options.blocks() > 0) {
            console.runBlocks(options.blocks());
        }

        int[] frame = console.renderFrame();
        PpmFrameWriter.write(options.frame(), frame, GbaVideo.WIDTH, GbaVideo.HEIGHT);
        System.out.println("Frame escrito em " + options.frame().toAbsolutePath());
        System.out.println("PC=0x" + Integer.toHexString(console.cpu().programCounter()));
    }

    private static void printUsage() {
        System.out.println("""
                Uso:
                  java dev.vitorsilverio.gbaemu.Main --rom game.gba [--bios gba_bios.bin] [--steps N] [--blocks N] [--frame out.ppm]

                Exemplos:
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--bios gba_bios.bin --rom game.gba --steps 1000 --frame boot.ppm"
                  mvn exec:java -Dexec.mainClass=dev.vitorsilverio.gbaemu.Main -Dexec.args="--rom game.gba --blocks 1 --frame frame.ppm"
                """);
    }

    private record CliOptions(Path rom, Path bios, int steps, int blocks, Path frame) {
        private static CliOptions parse(String[] args) {
            Path rom = null;
            Path bios = null;
            Path frame = Path.of("frame.ppm");
            int steps = 0;
            int blocks = 0;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--rom" -> rom = Path.of(value(args, ++i, arg));
                    case "--bios" -> bios = Path.of(value(args, ++i, arg));
                    case "--steps" -> steps = Integer.parseInt(value(args, ++i, arg));
                    case "--blocks" -> blocks = Integer.parseInt(value(args, ++i, arg));
                    case "--frame" -> frame = Path.of(value(args, ++i, arg));
                    case "--help", "-h" -> {
                        return null;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (rom == null) {
                return null;
            }
            return new CliOptions(rom, bios, steps, blocks, frame);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
