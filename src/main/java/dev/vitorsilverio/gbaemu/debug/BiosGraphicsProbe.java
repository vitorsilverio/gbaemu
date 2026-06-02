package dev.vitorsilverio.gbaemu.debug;

import dev.vitorsilverio.gbaemu.core.GbaConsole;

import java.nio.file.Files;
import java.nio.file.Path;

/// Ferramenta local para inspecionar o estado grafico deixado pela BIOS.
public final class BiosGraphicsProbe {
    private BiosGraphicsProbe() {
    }

    public static void main(String[] args) throws Exception {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : 1_800_000;
        GbaConsole console = GbaConsole.fromBiosAndRom(
                Files.readAllBytes(Path.of("gba_bios.bin")),
                Files.readAllBytes(Path.of("pokefirered.gba")));
        console.stepCpu(steps);

        var memory = console.memory();
        System.out.printf("PC=%08X DISPCNT=%04X VCOUNT=%d%n",
                console.cpu().programCounter(),
                memory.read16(0x04000000),
                memory.read16(0x04000006));

        int visible = 0;
        int affine = 0;
        int nonzero = 0;
        for (int object = 0; object < 128; object++) {
            int base = 0x07000000 + object * 8;
            int attr0 = memory.read16(base);
            int attr1 = memory.read16(base + 2);
            int attr2 = memory.read16(base + 4);
            if ((attr0 | attr1 | attr2) != 0) {
                nonzero++;
            }
            boolean isAffine = (attr0 & 0x0100) != 0;
            boolean disabled = !isAffine && (attr0 & 0x0200) != 0;
            if (!disabled) {
                visible++;
            }
            if (isAffine) {
                affine++;
            }
            if ((attr0 | attr1 | attr2) != 0 || object < 16) {
                printObject(memory, object, attr0, attr1, attr2);
            }
        }
        System.out.printf("nonzero=%d visible=%d affine=%d%n", nonzero, visible, affine);
        for (int matrix = 0; matrix < 8; matrix++) {
            int base = 0x07000000 + matrix * 32;
            System.out.printf("mat%02d pa=%04X pb=%04X pc=%04X pd=%04X%n",
                    matrix,
                    memory.read16(base + 6),
                    memory.read16(base + 14),
                    memory.read16(base + 22),
                    memory.read16(base + 30));
        }
    }

    private static void printObject(dev.vitorsilverio.gbaemu.memory.GbaMemory memory, int object, int attr0, int attr1, int attr2) {
        int shape = (attr0 >>> 14) & 3;
        int size = (attr1 >>> 14) & 3;
        int matrix = (attr1 >>> 9) & 31;
        int x = attr1 & 0x1ff;
        int y = attr0 & 0xff;
        int tile = attr2 & 0x3ff;
        int pal = (attr2 >>> 12) & 15;
        System.out.printf("obj%03d a0=%04X a1=%04X a2=%04X x=%03d y=%03d shape=%d size=%d aff=%s mat=%02d tile=%03d pal=%d%n",
                object, attr0, attr1, attr2, x, y, shape, size, (attr0 & 0x0100) != 0, matrix, tile, pal);
    }
}
