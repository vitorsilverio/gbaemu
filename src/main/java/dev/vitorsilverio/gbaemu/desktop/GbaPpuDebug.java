package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.memory.GbaBus;

/// Decodes GBA video memory into viewable images for the PPU debug window, reading
/// VRAM/palette/OAM through the bus on demand. Kept free of the core renderer (and of
/// AWT) so it can be unit-tested headlessly and never perturbs the live PPU state.
///
/// Index 0 of any palette is rendered fully transparent (argb 0) so tile/map/sprite
/// boundaries stay visible against the panel background.
public final class GbaPpuDebug {
    public static final int BG_PALETTE = 0x05000000;
    public static final int OBJ_PALETTE = 0x05000200;
    private static final int VRAM = 0x06000000;
    private static final int OBJ_TILES = 0x06010000;
    private static final int OAM = 0x07000000;
    private static final int CHAR_BLOCK_BYTES = 0x4000;

    /// Object width/height in pixels indexed by [shape][size]; shape 3 is invalid (8x8).
    private static final int[][][] OBJECT_DIMENSIONS = {
            {{8, 8}, {16, 16}, {32, 32}, {64, 64}},
            {{16, 8}, {32, 8}, {32, 16}, {64, 32}},
            {{8, 16}, {8, 32}, {16, 32}, {32, 64}}
    };

    private GbaPpuDebug() {
    }

    /// A decoded image: ARGB pixels row-major, {@code width * height} long.
    public record DebugImage(int width, int height, int[] argb) {
    }

    /// Renders the full background map for layer {@code bg} honouring the current video
    /// mode (text, affine or bitmap), or {@code null} when the layer is not usable in
    /// that mode. The image is the whole map, not just the visible 240x160 window.
    public static DebugImage backgroundLayer(GbaBus bus, int bg) {
        int dispcnt = bus.read16(0x04000000);
        int mode = dispcnt & 7;
        if (mode >= 3) {
            return bg == 2 ? bitmapLayer(bus, dispcnt, mode) : null;
        }
        boolean affine = (mode == 1 && bg == 2) || (mode == 2 && (bg == 2 || bg == 3));
        boolean usable = switch (mode) {
            case 0 -> true;
            case 1 -> bg <= 2;
            case 2 -> bg == 2 || bg == 3;
            default -> false;
        };
        if (!usable) {
            return null;
        }
        int cnt = bus.read16(0x04000008 + bg * 2);
        return affine ? affineBackground(bus, cnt) : textBackground(bus, cnt);
    }

    private static DebugImage textBackground(GbaBus bus, int cnt) {
        int charBase = VRAM + ((cnt >> 2) & 3) * CHAR_BLOCK_BYTES;
        int screenBase = VRAM + ((cnt >> 8) & 0x1F) * 0x800;
        boolean eightBpp = (cnt & 0x80) != 0;
        int sizeBits = (cnt >> 14) & 3;
        int widthTiles = (sizeBits == 1 || sizeBits == 3) ? 64 : 32;
        int heightTiles = (sizeBits == 2 || sizeBits == 3) ? 64 : 32;
        int width = widthTiles * 8;
        int height = heightTiles * 8;
        int[] argb = new int[width * height];
        int blocksPerRow = widthTiles / 32;
        for (int tileY = 0; tileY < heightTiles; tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int screenBlock = (tileX / 32) + (tileY / 32) * blocksPerRow;
                int entryAddr = screenBase + screenBlock * 0x800
                        + ((tileY % 32) * 32 + (tileX % 32)) * 2;
                int entry = bus.read16(entryAddr);
                int tile = entry & 0x3FF;
                boolean hflip = (entry & 0x400) != 0;
                boolean vflip = (entry & 0x800) != 0;
                int paletteBank = (entry >> 12) & 0xF;
                int tileAddress = charBase + tile * (eightBpp ? 64 : 32);
                decodeTile(bus, argb, width, tileX * 8, tileY * 8, tileAddress, eightBpp,
                        BG_PALETTE, eightBpp ? 0 : paletteBank * 16, hflip, vflip);
            }
        }
        return new DebugImage(width, height, argb);
    }

    private static DebugImage affineBackground(GbaBus bus, int cnt) {
        int charBase = VRAM + ((cnt >> 2) & 3) * CHAR_BLOCK_BYTES;
        int screenBase = VRAM + ((cnt >> 8) & 0x1F) * 0x800;
        int sizeBits = (cnt >> 14) & 3;
        int dimTiles = switch (sizeBits) {
            case 0 -> 16;
            case 1 -> 32;
            case 2 -> 64;
            default -> 128;
        };
        int side = dimTiles * 8;
        int[] argb = new int[side * side];
        for (int tileY = 0; tileY < dimTiles; tileY++) {
            for (int tileX = 0; tileX < dimTiles; tileX++) {
                int tile = bus.read8(screenBase + tileY * dimTiles + tileX) & 0xFF;
                int tileAddress = charBase + tile * 64; // affine maps are always 8bpp
                decodeTile(bus, argb, side, tileX * 8, tileY * 8, tileAddress, true,
                        BG_PALETTE, 0, false, false);
            }
        }
        return new DebugImage(side, side, argb);
    }

    private static DebugImage bitmapLayer(GbaBus bus, int dispcnt, int mode) {
        int width = mode == 5 ? 160 : 240;
        int height = mode == 5 ? 128 : 160;
        int[] argb = new int[width * height];
        if (mode == 4) {
            int frameBase = (dispcnt & 0x10) != 0 ? VRAM + 0xA000 : VRAM;
            for (int i = 0; i < width * height; i++) {
                int index = bus.read8(frameBase + i) & 0xFF;
                argb[i] = index == 0 ? 0 : bgr555ToArgb(bus.read16(BG_PALETTE + index * 2));
            }
            return new DebugImage(width, height, argb);
        }
        int frameBase = mode == 5 && (dispcnt & 0x10) != 0 ? VRAM + 0xA000 : VRAM;
        for (int i = 0; i < width * height; i++) {
            argb[i] = bgr555ToArgb(bus.read16(frameBase + i * 2));
        }
        return new DebugImage(width, height, argb);
    }

    /// Renders one 16-tiles-wide character block as a tile sheet. Blocks 0-3 are the BG
    /// char blocks (BG palette); blocks 4-5 are the OBJ tile region (OBJ palette).
    public static DebugImage charBlock(GbaBus bus, int charBlock, int paletteBank, boolean eightBpp) {
        int base = charBlock < 4 ? VRAM + charBlock * CHAR_BLOCK_BYTES
                : OBJ_TILES + (charBlock - 4) * CHAR_BLOCK_BYTES;
        int paletteBase = charBlock < 4 ? BG_PALETTE : OBJ_PALETTE;
        int tileBytes = eightBpp ? 64 : 32;
        int tileCount = CHAR_BLOCK_BYTES / tileBytes;
        int columns = 16;
        int rows = tileCount / columns;
        int width = columns * 8;
        int height = rows * 8;
        int[] argb = new int[width * height];
        int bankOffset = eightBpp ? 0 : paletteBank * 16;
        for (int tile = 0; tile < tileCount; tile++) {
            int tileX = (tile % columns) * 8;
            int tileY = (tile / columns) * 8;
            decodeTile(bus, argb, width, tileX, tileY, base + tile * tileBytes, eightBpp,
                    paletteBase, bankOffset, false, false);
        }
        return new DebugImage(width, height, argb);
    }

    /// Decodes a single object (sprite) from OAM into an image of its native size.
    public static DebugImage spriteImage(GbaBus bus, int oamIndex) {
        int a0 = bus.read16(OAM + oamIndex * 8);
        int a1 = bus.read16(OAM + oamIndex * 8 + 2);
        int a2 = bus.read16(OAM + oamIndex * 8 + 4);
        int shape = (a0 >> 14) & 3;
        int size = (a1 >> 14) & 3;
        int[] wh = shape == 3 ? new int[]{8, 8} : OBJECT_DIMENSIONS[shape][size];
        int width = wh[0];
        int height = wh[1];
        boolean eightBpp = (a0 & 0x2000) != 0;
        int baseTile = a2 & 0x3FF;
        int paletteBank = (a2 >> 12) & 0xF;
        boolean oneDimensional = (bus.read16(0x04000000) & (1 << 6)) != 0;
        int widthTiles = width / 8;
        int heightTiles = height / 8;
        int slotStep = eightBpp ? 2 : 1;
        int[] argb = new int[width * height];
        for (int tileY = 0; tileY < heightTiles; tileY++) {
            for (int tileX = 0; tileX < widthTiles; tileX++) {
                int tileNum = oneDimensional
                        ? baseTile + (tileY * widthTiles + tileX) * slotStep
                        : baseTile + tileY * 32 + tileX * slotStep;
                int tileAddress = OBJ_TILES + (tileNum & 0x3FF) * 32;
                decodeTile(bus, argb, width, tileX * 8, tileY * 8, tileAddress, eightBpp,
                        OBJ_PALETTE, eightBpp ? 0 : paletteBank * 16, false, false);
            }
        }
        return new DebugImage(width, height, argb);
    }

    /// Decodes an 8x8 tile at an absolute byte address into the target buffer at (dstX,dstY).
    private static void decodeTile(GbaBus bus, int[] argb, int imageWidth, int dstX, int dstY,
            int tileAddress, boolean eightBpp, int paletteBase, int paletteBankOffset,
            boolean hflip, boolean vflip) {
        for (int row = 0; row < 8; row++) {
            int sourceRow = vflip ? 7 - row : row;
            int rowStart = (dstY + row) * imageWidth + dstX;
            for (int col = 0; col < 8; col++) {
                int sourceCol = hflip ? 7 - col : col;
                int index;
                if (eightBpp) {
                    index = bus.read8(tileAddress + sourceRow * 8 + sourceCol) & 0xFF;
                } else {
                    int packed = bus.read8(tileAddress + sourceRow * 4 + (sourceCol >> 1)) & 0xFF;
                    index = (sourceCol & 1) == 0 ? packed & 0xF : (packed >> 4) & 0xF;
                }
                argb[rowStart + col] = index == 0
                        ? 0
                        : bgr555ToArgb(bus.read16(paletteBase + (paletteBankOffset + index) * 2));
            }
        }
    }

    public static int bgr555ToArgb(int value) {
        int r = (value & 0x1F) * 255 / 31;
        int g = ((value >> 5) & 0x1F) * 255 / 31;
        int b = ((value >> 10) & 0x1F) * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
