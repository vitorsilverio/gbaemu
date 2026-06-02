package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.HashSet;
import java.util.Set;

/// Pequeno painel de instrumentos para o bring-up da PPU.
///
/// A BIOS/ROM pode ficar visualmente preta mesmo quando a CPU ja esta
/// avancando. Estas estatisticas ajudam a separar "nada escreveu video ainda"
/// de "o renderer recebeu dados, mas ainda nao desenha a camada esperada".
public record GbaVideoFrameStats(
        int dispcnt,
        int mode,
        boolean forcedBlank,
        int enabledBackgroundMask,
        boolean objectsEnabled,
        int backdropArgb,
        int uniqueColors,
        int nonBackdropPixels,
        int nonBlackPixels,
        int paletteNonZeroHalfwords,
        int vramNonZeroBytes,
        int visibleObjects) {
    private static final int DISPCNT = 0x04000000;
    private static final int PALETTE = 0x05000000;
    private static final int VRAM = 0x06000000;
    private static final int OAM = 0x07000000;
    private static final int BG_ENABLE_SHIFT = 8;
    private static final int OBJ_ENABLE = 1 << 12;
    private static final int FORCED_BLANK = 1 << 7;

    public static GbaVideoFrameStats capture(AddressSpace memory, int[] frame) {
        int dispcnt = memory.read16(DISPCNT);
        int backdrop = GbaVideo.bgr555ToArgb(memory.read16(PALETTE));
        int nonBackdrop = 0;
        int nonBlack = 0;
        Set<Integer> colors = new HashSet<>();
        for (int color : frame) {
            colors.add(color);
            if (color != backdrop) {
                nonBackdrop++;
            }
            if (color != 0xFF000000) {
                nonBlack++;
            }
        }

        return new GbaVideoFrameStats(
                dispcnt,
                dispcnt & 0x7,
                (dispcnt & FORCED_BLANK) != 0,
                (dispcnt >>> BG_ENABLE_SHIFT) & 0xF,
                (dispcnt & OBJ_ENABLE) != 0,
                backdrop,
                colors.size(),
                nonBackdrop,
                nonBlack,
                countNonZeroHalfwords(memory, PALETTE, 0x400),
                countNonZeroBytes(memory, VRAM, 0x18000),
                countVisibleObjects(memory));
    }

    public String compactSummary() {
        return "mode=" + mode
                + " DISPCNT=0x" + hex16(dispcnt)
                + " BG=" + enabledBackgroundMask
                + " OBJ=" + objectsEnabled
                + " forcedBlank=" + forcedBlank
                + " colors=" + uniqueColors
                + " nonBackdrop=" + nonBackdropPixels
                + " nonBlack=" + nonBlackPixels
                + " palNZ=" + paletteNonZeroHalfwords
                + " vramNZ=" + vramNonZeroBytes
                + " objVisible=" + visibleObjects;
    }

    private static int countNonZeroHalfwords(AddressSpace memory, int base, int size) {
        int count = 0;
        for (int offset = 0; offset < size; offset += 2) {
            if (memory.read16(base + offset) != 0) {
                count++;
            }
        }
        return count;
    }

    private static int countNonZeroBytes(AddressSpace memory, int base, int size) {
        int count = 0;
        for (int offset = 0; offset < size; offset++) {
            if (memory.read8(base + offset) != 0) {
                count++;
            }
        }
        return count;
    }

    private static int countVisibleObjects(AddressSpace memory) {
        int count = 0;
        for (int object = 0; object < 128; object++) {
            int attr0 = memory.read16(OAM + object * 8);
            boolean affine = (attr0 & (1 << 8)) != 0;
            boolean disabled = !affine && (attr0 & (1 << 9)) != 0;
            if (!disabled) {
                count++;
            }
        }
        return count;
    }

    private static String hex16(int value) {
        return String.format("%04X", value & 0xFFFF);
    }
}
