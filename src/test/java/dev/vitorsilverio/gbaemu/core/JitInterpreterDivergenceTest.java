package dev.vitorsilverio.gbaemu.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/// Local-only differential: boots the SAME ROM under the real BIOS on both the JIT
/// (JVM-bytecode + GBA optimizer) and the interpreted IR backend, stepping both in
/// lockstep one CPU block at a time and comparing the full register file + CPSR after
/// each block. Reports the FIRST divergence (block index, PC, the differing registers).
///
/// This pinpoints the instruction pattern the JIT/optimizer miscompiles — the user sees
/// JIT-only corruption (upside-down BIOS logo, garbled audio) that is absent on the
/// interpreted path. Skips unless gba_bios.bin is present (real BIOS is not in the repo).
///
///   mvn -o -Dtest=JitInterpreterDivergenceTest -Dgba.rom=roms/mariokart.gba test
class JitInterpreterDivergenceTest {

    /// Este harness compara os DOIS consoles chamando {@code runBlocks(CHUNK)} o mesmo número de
    /// vezes e assume que cada chamada avança exatamente um bloco de CPU — a premissa por trás da
    /// busca em duas fases (grossa/fina). O encadeamento de blocos (task C5, default do
    /// {@code GbaConsole} desde então) quebra essa premissa: cada chamada pode consumir VÁRIOS
    /// blocos, e o JIT (tiered) e o interpretado (threshold direto) aquecem o inline cache em
    /// ritmos diferentes, então divergem em quantos blocos cada lado encadeia por chamada — uma
    /// divergência de CONTABILIDADE do harness, não da semântica da instrução. Desligado aqui para
    /// manter a granularidade bloco-a-bloco que esta ferramenta de diagnóstico exige.
    private static void disableChaining(GbaConsole... consoles) {
        for (GbaConsole console : consoles) {
            console.runtime().setChainCycleBudget(0);
        }
    }

    private static byte[] readOrSkip(String property, String fallback) throws Exception {
        Path path = Path.of(System.getProperty(property, fallback));
        Assumptions.assumeTrue(Files.exists(path), "missing " + path + " (set -D" + property + ")");
        return Files.readAllBytes(path);
    }

    // Memory regions to compare (base, wordCount, nameIndex): EWRAM, IWRAM, PAL, VRAM, OAM.
    private static final int[][] REGIONS = {
            {0x02000000, 0x40000 / 4, 0}, // EWRAM 256K
            {0x03000000, 0x08000 / 4, 1}, // IWRAM 32K
            {0x05000000, 0x00400 / 4, 2}, // Palette 1K
            {0x06000000, 0x18000 / 4, 3}, // VRAM 96K
            {0x07000000, 0x00400 / 4, 4}, // OAM 1K
    };
    private static final String[] REGION_NAMES = {"EWRAM", "IWRAM", "PAL", "VRAM", "OAM"};
    private static final int CHUNK = 1000;

    @Test
    void findFirstDivergence() throws Exception {
        byte[] bios = readOrSkip("gba.bios", "gba_bios.bin");
        byte[] rom = readOrSkip("gba.rom", "roms/Mario Kart - Super Circuit (USA).gba");

        // Phase 1 (coarse): step in CHUNK-block strides; find the first chunk where state diverges.
        GbaConsole jit = GbaConsole.fromBiosAndRom(bios, rom, true);
        GbaConsole interp = GbaConsole.fromBiosAndRom(bios, rom, false);
        disableChaining(jit, interp);
        int maxChunks = 20000;
        long blocksBeforeBadChunk = -1;
        for (int c = 0; c < maxChunks; c++) {
            jit.runBlocks(CHUNK);
            interp.runBlocks(CHUNK);
            if (divergence(jit, interp) != -1) {
                blocksBeforeBadChunk = (long) c * CHUNK;
                break;
            }
        }
        if (blocksBeforeBadChunk < 0) {
            System.err.println("No divergence in " + ((long) maxChunks * CHUNK) + " blocks");
            return;
        }

        // Phase 2 (fine): fresh boot, fast-forward to the start of the bad chunk, then step one
        // block at a time comparing regs + memory until the first divergence.
        GbaConsole j2 = GbaConsole.fromBiosAndRom(bios, rom, true);
        GbaConsole i2 = GbaConsole.fromBiosAndRom(bios, rom, false);
        disableChaining(j2, i2);
        if (blocksBeforeBadChunk > 0) {
            j2.runBlocks((int) blocksBeforeBadChunk);
            i2.runBlocks((int) blocksBeforeBadChunk);
        }
        int[] prevRegs = snapshot(j2);
        for (int i = 0; i < CHUNK * 2; i++) {
            j2.runBlocks(1);
            i2.runBlocks(1);
            int[] j = snapshot(j2);
            int[] k = snapshot(i2);
            int memRegion = firstDivergentRegion(j2, i2);
            if (!java.util.Arrays.equals(j, k) || memRegion >= 0) {
                long globalBlock = blocksBeforeBadChunk + i;
                StringBuilder sb = new StringBuilder();
                sb.append("DIVERGENCE at block ").append(globalBlock)
                  .append(" (cycles~").append(j2.cpu().cycles()).append(")\n");
                int entry = prevRegs[15];
                boolean thumb = (prevRegs[16] & 0x20) != 0;
                sb.append("  prev PC (block entry): 0x").append(Integer.toHexString(entry))
                  .append(thumb ? " [THUMB]" : " [ARM]").append('\n');
                if (memRegion >= 0) {
                    int base = REGIONS[memRegion][0];
                    int words = REGIONS[memRegion][1];
                    sb.append("  MEMORY diverges in ").append(REGION_NAMES[REGIONS[memRegion][2]]).append('\n');
                    int shown = 0;
                    for (int w = 0; w < words && shown < 8; w++) {
                        int a = base + w * 4;
                        int jv = j2.bus().read32(a), kv = i2.bus().read32(a);
                        if (jv != kv) {
                            sb.append(String.format("    [%08X] JIT=%08X  INTERP=%08X%n", a, jv, kv));
                            shown++;
                        }
                    }
                } else {
                    sb.append("  (registers diverge; memory matches)\n");
                }
                dumpBlock(sb, j2, entry, thumb);
                String[] names = {"r0","r1","r2","r3","r4","r5","r6","r7","r8","r9","r10","r11","r12","sp","lr","pc","cpsr"};
                for (int r = 0; r < j.length; r++) {
                    if (j[r] != k[r]) {
                        sb.append(String.format("  %-4s JIT=%08X  INTERP=%08X%n", names[r], j[r], k[r]));
                    }
                }
                throw new AssertionError(sb.toString());
            }
            prevRegs = j;
        }
        throw new AssertionError("Phase 1 found a bad chunk near block " + blocksBeforeBadChunk
                + " but Phase 2 could not reproduce it within " + (CHUNK * 2) + " blocks");
    }

    /// -1 if regs+memory match; -2 if regs differ; else region index that differs.
    private static int divergence(GbaConsole a, GbaConsole b) {
        if (!java.util.Arrays.equals(snapshot(a), snapshot(b))) {
            return -2;
        }
        return firstDivergentRegion(a, b);
    }

    /// Returns the index into {@code REGIONS} of the first region whose memory differs between
    /// the two consoles, or -1 if all compared regions match. Uses a fast checksum.
    private static int firstDivergentRegion(GbaConsole a, GbaConsole b) {
        for (int r = 0; r < REGIONS.length; r++) {
            int base = REGIONS[r][0];
            int words = REGIONS[r][1];
            long ca = 0, cb = 0;
            for (int w = 0; w < words; w++) {
                int addr = base + w * 4;
                ca = ca * 1099511628211L + (a.bus().read32(addr) & 0xFFFFFFFFL);
                cb = cb * 1099511628211L + (b.bus().read32(addr) & 0xFFFFFFFFL);
            }
            if (ca != cb) {
                return r;
            }
        }
        return -1;
    }

    private static void dumpBlock(StringBuilder sb, GbaConsole console, int pc, boolean thumb) {
        try {
            var decoder = thumb
                    ? new dev.vitorsilverio.armjitter.decoder.ThumbDecoder(
                            dev.vitorsilverio.armjitter.arch.ArmArchitecture.ARMV4T)
                    : new dev.vitorsilverio.armjitter.decoder.ArmDecoder(
                            dev.vitorsilverio.armjitter.arch.ArmArchitecture.ARMV4T);
            var lifter = new dev.vitorsilverio.armjitter.ir.StandardIrBlockLifter(
                    decoder, new dev.vitorsilverio.armjitter.ir.StandardIrBuilder());
            var raw = lifter.lift(console.bus(), pc, 64);
            var opt = dev.vitorsilverio.armjitter.ir.opt.StandardIrOptimizer.gba().optimize(raw);
            sb.append("  --- RAW IR (").append(raw.operations().size()).append(" ops) ---\n");
            for (var op : raw.operations()) {
                boolean nat = dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(op);
                sb.append("    ").append(nat ? "[native] " : "[FALLBK] ").append(op).append('\n');
            }
            sb.append("  --- OPTIMIZED IR (").append(opt.operations().size()).append(" ops) ---\n");
            for (var op : opt.operations()) {
                boolean nat = dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(op);
                sb.append("    ").append(nat ? "[native] " : "[FALLBK] ").append(op).append('\n');
            }
            sb.append("  block native-supported (raw): ")
              .append(dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(raw))
              .append("  (optimized): ")
              .append(dev.vitorsilverio.armjitter.codegen.jvm.AsmNativePolicy.supports(opt)).append('\n');
        } catch (RuntimeException e) {
            sb.append("  (dumpBlock failed: ").append(e).append(")\n");
        }
    }

    private static int[] snapshot(GbaConsole console) {
        int[] regs = new int[17];
        for (int r = 0; r < 16; r++) {
            regs[r] = console.cpu().register(r);
        }
        regs[16] = console.cpu().cpsr().get();
        return regs;
    }
}
