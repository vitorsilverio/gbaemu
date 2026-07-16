package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Arrays;

/// Renderer da PPU do GBA, baseado em scanline.
///
/// Cada scanline e desenhada com o estado dos registradores no instante em que a linha
/// e exibida (ver {@link #renderScanline}), permitindo efeitos por-linha (affine variando
/// por scanline, scroll/prioridade/janela trocados no HBlank). A composicao mantem as duas
/// camadas mais a frente por pixel para suportar alpha blending (BLDCNT/BLDALPHA),
/// fade de brilho (BLDY) e sprites semi-transparentes. {@link #renderFrame} desenha o
/// quadro inteiro chamando as 160 linhas em sequencia (caminho headless/testes).
public final class GbaVideo {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 160;

    private static final int DISPCNT = 0x04000000;
    private static final int BG0CNT = 0x04000008;
    private static final int BG0HOFS = 0x04000010;
    private static final int BG0VOFS = 0x04000012;
    private static final int BG2PA = 0x04000020;
    private static final int BG2PB = 0x04000022;
    private static final int BG2PC = 0x04000024;
    private static final int BG2PD = 0x04000026;
    private static final int BG2X = 0x04000028;
    private static final int BG2Y = 0x0400002C;
    private static final int BG3PA = 0x04000030;
    private static final int BG3PB = 0x04000032;
    private static final int BG3PC = 0x04000034;
    private static final int BG3PD = 0x04000036;
    private static final int BG3X = 0x04000038;
    private static final int BG3Y = 0x0400003C;
    private static final int WIN0H = 0x04000040;
    private static final int WIN1H = 0x04000042;
    private static final int WIN0V = 0x04000044;
    private static final int WIN1V = 0x04000046;
    private static final int WININ = 0x04000048;
    private static final int WINOUT = 0x0400004A;
    private static final int BLDCNT = 0x04000050;
    private static final int BLDALPHA = 0x04000052;
    private static final int BLDY = 0x04000054;
    private static final int FORCED_BLANK = 1 << 7;
    private static final int BG_ENABLE_SHIFT = 8;
    private static final int BG2_ENABLE = 1 << 10;
    private static final int OBJ_ENABLE = 1 << 12;
    private static final int OBJ_1D_MAPPING = 1 << 6;
    private static final int WIN0_ENABLE = 1 << 13;
    private static final int WIN1_ENABLE = 1 << 14;
    private static final int OBJ_WINDOW_ENABLE = 1 << 15;
    private static final int WINDOW_OBJ_ENABLE = 1 << 4;
    private static final int WINDOW_BLEND_ENABLE = 1 << 5;
    private static final int WINDOW_ALL_LAYERS = 0x1F;
    private static final int WINOUT_OBJ_WINDOW_SHIFT = 8;
    private static final int OBJ_MODE_SEMI_TRANSPARENT = 1;
    private static final int OBJ_MODE_WINDOW = 2;
    private static final int VRAM = 0x06000000;
    private static final int OAM = 0x07000000;
    private static final int PALETTE = 0x05000000;
    private static final int OBJ_PALETTE = 0x05000200;
    private static final int OBJ_TILES = 0x06010000;
    private static final int FRAME_1 = 0x0000A000;
    private static final int MODE_5_WIDTH = 160;
    private static final int MODE_5_HEIGHT = 128;
    private static final int TILE_SIZE = 8;
    private static final int MAP_BLOCK_SIZE = 0x800;
    private static final int CHAR_BLOCK_SIZE = 0x4000;
    private static final int TRANSPARENT = -1;
    // Layer ids, chosen to match the BLDCNT target bit positions (bit0=BG0..bit4=OBJ,bit5=backdrop).
    private static final int LAYER_OBJ = 4;
    private static final int LAYER_BACKDROP = 5;
    private static final int BACKDROP_PRIORITY = 5;

    private final int[] framebuffer = new int[WIDTH * HEIGHT];
    private volatile int[] presentedFrame = new int[WIDTH * HEIGHT];

    // Per-scanline compositor: the two front-most layers per pixel (colours are BGR555),
    // used to apply alpha blending / fade after all layers are gathered.
    private final int[] topColor = new int[WIDTH];
    private final int[] topPriority = new int[WIDTH];
    private final int[] topLayer = new int[WIDTH];
    private final boolean[] topObjSemi = new boolean[WIDTH];
    private final int[] secondColor = new int[WIDTH];
    private final int[] secondPriority = new int[WIDTH];
    private final int[] secondLayer = new int[WIDTH];
    // Front-most OBJ pixel per column for this scanline (gathered before BG compositing).
    private final int[] objColor = new int[WIDTH];
    private final int[] objPriority = new int[WIDTH];
    private final boolean[] objSemi = new boolean[WIDTH];
    private final byte[] windowMaskLine = new byte[WIDTH];
    // Per-scanline coverage of OBJ-window-mode sprites (attr0 mode==2): these sprites are never
    // drawn as pixels, they only define a window region (their opaque texels become "inside the
    // OBJ window"), consulted by activeWindowMaskLine(). Recomputed before the window mask.
    private final boolean[] objWindowCoverage = new boolean[WIDTH];

    // Internal affine reference points (BG2/BG3), reloaded from the register at frame top
    // and on a mid-frame rewrite, advanced by PB/PD per scanline otherwise.
    private int affineRefX2;
    private int affineRefY2;
    private int affineRefX3;
    private int affineRefY3;
    private int lastBg2xReg;
    private int lastBg2yReg;
    private int lastBg3xReg;
    private int lastBg3yReg;

    public int[] renderFrame(AddressSpace memory) {
        for (int line = 0; line < HEIGHT; line++) {
            renderScanline(memory, line);
        }
        return framebufferSnapshot();
    }

    public void renderScanline(AddressSpace memory, int line) {
        if (line < 0 || line >= HEIGHT) {
            return;
        }
        syncAffineReferences(memory, line);

        int dispcnt = memory.read16(DISPCNT);
        int lineBase = line * WIDTH;
        if ((dispcnt & FORCED_BLANK) != 0) {
            Arrays.fill(framebuffer, lineBase, lineBase + WIDTH, 0xFFFFFFFF);
            advanceAffineReferences(memory);
            if (line == HEIGHT - 1) {
                present();
            }
            return;
        }

        int backdrop = memory.read16(PALETTE) & 0x7FFF;
        for (int x = 0; x < WIDTH; x++) {
            topColor[x] = backdrop;
            topPriority[x] = BACKDROP_PRIORITY;
            topLayer[x] = LAYER_BACKDROP;
            topObjSemi[x] = false;
            secondColor[x] = backdrop;
            secondPriority[x] = BACKDROP_PRIORITY;
            secondLayer[x] = LAYER_BACKDROP;
            objColor[x] = TRANSPARENT;
        }

        if ((dispcnt & OBJ_ENABLE) != 0 && (dispcnt & OBJ_WINDOW_ENABLE) != 0) {
            computeObjWindowCoverage(memory, dispcnt, line);
        }
        byte[] mask = activeWindowMaskLine(memory, dispcnt, line);
        if ((dispcnt & OBJ_ENABLE) != 0) {
            gatherObjectsLine(memory, dispcnt, line, mask);
        }
        switch (dispcnt & 0x7) {
            case 0 -> renderTileBackgroundsLine(memory, dispcnt, 0b1111, 0, line, mask);
            case 1 -> renderTileBackgroundsLine(memory, dispcnt, 0b0011, 0b0100, line, mask);
            case 2 -> renderTileBackgroundsLine(memory, dispcnt, 0, 0b1100, line, mask);
            case 3 -> renderMode3Line(memory, line, mask);
            case 4 -> renderMode4Line(memory, (dispcnt & (1 << 4)) != 0, line, mask);
            case 5 -> renderMode5Line(memory, (dispcnt & (1 << 4)) != 0, line, mask);
            default -> {
            }
        }
        for (int x = 0; x < WIDTH; x++) {
            if (objColor[x] != TRANSPARENT) {
                submit(x, objColor[x], objPriority[x], LAYER_OBJ, objSemi[x]);
            }
        }

        int bldcnt = memory.read16(BLDCNT);
        int bldalpha = memory.read16(BLDALPHA);
        int bldy = memory.read16(BLDY);
        for (int x = 0; x < WIDTH; x++) {
            framebuffer[lineBase + x] = bgr555ToArgb(composite(x, bldcnt, bldalpha, bldy, mask));
        }

        advanceAffineReferences(memory);
        if (line == HEIGHT - 1) {
            present();
        }
    }

    public int[] framebufferSnapshot() {
        return Arrays.copyOf(framebuffer, framebuffer.length);
    }

    public int[] presentedFrame() {
        return presentedFrame;
    }

    private void present() {
        presentedFrame = Arrays.copyOf(framebuffer, framebuffer.length);
    }

    /// Inserts one opaque layer pixel into the per-pixel top/second slots, keeping them
    /// ordered front-to-back (lower priority number wins; OBJ beats BG at equal priority;
    /// lower BG number beats higher; backdrop is always last).
    private void submit(int x, int color, int priority, int layer, boolean objIsSemi) {
        if (isInFront(priority, layer, topPriority[x], topLayer[x])) {
            secondColor[x] = topColor[x];
            secondPriority[x] = topPriority[x];
            secondLayer[x] = topLayer[x];
            topColor[x] = color;
            topPriority[x] = priority;
            topLayer[x] = layer;
            topObjSemi[x] = objIsSemi;
        } else if (isInFront(priority, layer, secondPriority[x], secondLayer[x])) {
            secondColor[x] = color;
            secondPriority[x] = priority;
            secondLayer[x] = layer;
        }
    }

    private static boolean isInFront(int priorityA, int layerA, int priorityB, int layerB) {
        if (priorityA != priorityB) {
            return priorityA < priorityB;
        }
        boolean objA = layerA == LAYER_OBJ;
        boolean objB = layerB == LAYER_OBJ;
        if (objA != objB) {
            return objA;
        }
        return layerA < layerB;
    }

    /// Resolves the final BGR555 colour for a pixel, applying blending/fade. Blending is
    /// suppressed inside a window region whose blend bit is cleared.
    private int composite(int x, int bldcnt, int bldalpha, int bldy, byte[] mask) {
        int top = topColor[x];
        boolean blendAllowed = mask == null || (mask[x] & WINDOW_BLEND_ENABLE) != 0;
        boolean semiObj = topObjSemi[x] && topLayer[x] == LAYER_OBJ;

        if (blendAllowed && semiObj && isSecondTarget(bldcnt, secondLayer[x])) {
            return alphaBlend(top, secondColor[x], bldalpha);
        }
        if (!blendAllowed) {
            return top;
        }

        int mode = (bldcnt >>> 6) & 0x3;
        boolean topIsFirstTarget = (bldcnt & (1 << topLayer[x])) != 0;
        return switch (mode) {
            case 1 -> topIsFirstTarget && isSecondTarget(bldcnt, secondLayer[x])
                    ? alphaBlend(top, secondColor[x], bldalpha)
                    : top;
            case 2 -> topIsFirstTarget ? brighten(top, bldy) : top;
            case 3 -> topIsFirstTarget ? darken(top, bldy) : top;
            default -> top;
        };
    }

    private static boolean isSecondTarget(int bldcnt, int layer) {
        return (bldcnt & (1 << (8 + layer))) != 0;
    }

    private static int alphaBlend(int first, int second, int bldalpha) {
        int eva = Math.min(16, bldalpha & 0x1F);
        int evb = Math.min(16, (bldalpha >>> 8) & 0x1F);
        int r = Math.min(31, ((first & 0x1F) * eva + (second & 0x1F) * evb) >> 4);
        int g = Math.min(31, (((first >>> 5) & 0x1F) * eva + ((second >>> 5) & 0x1F) * evb) >> 4);
        int b = Math.min(31, (((first >>> 10) & 0x1F) * eva + ((second >>> 10) & 0x1F) * evb) >> 4);
        return r | (g << 5) | (b << 10);
    }

    private static int brighten(int color, int bldy) {
        int evy = Math.min(16, bldy & 0x1F);
        int r = color & 0x1F;
        int g = (color >>> 5) & 0x1F;
        int b = (color >>> 10) & 0x1F;
        r += ((31 - r) * evy) >> 4;
        g += ((31 - g) * evy) >> 4;
        b += ((31 - b) * evy) >> 4;
        return r | (g << 5) | (b << 10);
    }

    private static int darken(int color, int bldy) {
        int evy = Math.min(16, bldy & 0x1F);
        int r = color & 0x1F;
        int g = (color >>> 5) & 0x1F;
        int b = (color >>> 10) & 0x1F;
        r -= (r * evy) >> 4;
        g -= (g * evy) >> 4;
        b -= (b * evy) >> 4;
        return r | (g << 5) | (b << 10);
    }

    private void syncAffineReferences(AddressSpace memory, int line) {
        int bg2x = memory.read32(BG2X);
        int bg2y = memory.read32(BG2Y);
        int bg3x = memory.read32(BG3X);
        int bg3y = memory.read32(BG3Y);
        if (line == 0 || bg2x != lastBg2xReg) affineRefX2 = signed28(bg2x);
        if (line == 0 || bg2y != lastBg2yReg) affineRefY2 = signed28(bg2y);
        if (line == 0 || bg3x != lastBg3xReg) affineRefX3 = signed28(bg3x);
        if (line == 0 || bg3y != lastBg3yReg) affineRefY3 = signed28(bg3y);
        lastBg2xReg = bg2x;
        lastBg2yReg = bg2y;
        lastBg3xReg = bg3x;
        lastBg3yReg = bg3y;
    }

    private void advanceAffineReferences(AddressSpace memory) {
        affineRefX2 += signed16(memory.read16(BG2PB));
        affineRefY2 += signed16(memory.read16(BG2PD));
        affineRefX3 += signed16(memory.read16(BG3PB));
        affineRefY3 += signed16(memory.read16(BG3PD));
    }

    private void renderTileBackgroundsLine(
            AddressSpace memory,
            int dispcnt,
            int regularMask,
            int affineMask,
            int line,
            byte[] mask) {
        for (int bg = 0; bg < 4; bg++) {
            if ((dispcnt & (1 << (BG_ENABLE_SHIFT + bg))) == 0) {
                continue;
            }
            int bgcnt = bgControl(memory, bg);
            if ((regularMask & (1 << bg)) != 0) {
                renderRegularBackgroundLine(memory, bg, bgcnt, line, mask);
            } else if ((affineMask & (1 << bg)) != 0) {
                renderAffineBackgroundLine(memory, bg, bgcnt, line, mask);
            }
        }
    }

    private void renderRegularBackgroundLine(AddressSpace memory, int bg, int bgcnt, int screenY, byte[] mask) {
        int charBase = VRAM + ((bgcnt >>> 2) & 0x3) * CHAR_BLOCK_SIZE;
        boolean eightBpp = (bgcnt & (1 << 7)) != 0;
        int screenBase = VRAM + ((bgcnt >>> 8) & 0x1F) * MAP_BLOCK_SIZE;
        int size = (bgcnt >>> 14) & 0x3;
        int bgWidth = regularBackgroundWidth(size);
        int bgHeight = regularBackgroundHeight(size);
        int hofs = memory.read16(BG0HOFS + bg * 4) & 0x1FF;
        int vofs = memory.read16(BG0VOFS + bg * 4) & 0x1FF;
        int sourceY = Math.floorMod(screenY + vofs, bgHeight);
        int priority = bgcnt & 0x3;

        for (int screenX = 0; screenX < WIDTH; screenX++) {
            if (!layerEnabled(mask, screenX, bg)) {
                continue;
            }
            int sourceX = Math.floorMod(screenX + hofs, bgWidth);
            int color = regularBackgroundPixel(memory, charBase, screenBase, size, eightBpp, sourceX, sourceY);
            if (color != TRANSPARENT) {
                submit(screenX, color, priority, bg, false);
            }
        }
    }

    private void renderAffineBackgroundLine(AddressSpace memory, int bg, int bgcnt, int line, byte[] mask) {
        int charBase = VRAM + ((bgcnt >>> 2) & 0x3) * CHAR_BLOCK_SIZE;
        int screenBase = VRAM + ((bgcnt >>> 8) & 0x1F) * MAP_BLOCK_SIZE;
        boolean wrap = (bgcnt & (1 << 13)) != 0;
        int size = affineBackgroundSize((bgcnt >>> 14) & 0x3);
        int registerBase = bg == 2 ? BG2PA : BG3PA;
        int pa = signed16(memory.read16(registerBase));
        int pc = signed16(memory.read16(registerBase + 4));
        int sourceX = bg == 2 ? affineRefX2 : affineRefX3;
        int sourceY = bg == 2 ? affineRefY2 : affineRefY3;
        int priority = bgcnt & 0x3;

        for (int screenX = 0; screenX < WIDTH; screenX++) {
            if (layerEnabled(mask, screenX, bg)) {
                int color = affineBackgroundPixel(memory, charBase, screenBase, size, wrap, sourceX >> 8, sourceY >> 8);
                if (color != TRANSPARENT) {
                    submit(screenX, color, priority, bg, false);
                }
            }
            sourceX += pa;
            sourceY += pc;
        }
    }

    private int affineBackgroundPixel(
            AddressSpace memory,
            int charBase,
            int screenBase,
            int size,
            boolean wrap,
            int sourceX,
            int sourceY) {
        if (wrap) {
            sourceX = Math.floorMod(sourceX, size);
            sourceY = Math.floorMod(sourceY, size);
        } else if (sourceX < 0 || sourceY < 0 || sourceX >= size || sourceY >= size) {
            return TRANSPARENT;
        }

        int tileX = sourceX / TILE_SIZE;
        int tileY = sourceY / TILE_SIZE;
        int tilesPerRow = size / TILE_SIZE;
        int tileNumber = memory.read8(screenBase + tileY * tilesPerRow + tileX);
        int paletteIndex = readTile8Bpp(memory, charBase, tileNumber, sourceX & 7, sourceY & 7);
        if (paletteIndex == 0) {
            return TRANSPARENT;
        }
        return memory.read16(PALETTE + paletteIndex * 2) & 0x7FFF;
    }

    private int regularBackgroundPixel(
            AddressSpace memory,
            int charBase,
            int screenBase,
            int size,
            boolean eightBpp,
            int sourceX,
            int sourceY) {
        int screenBlock = regularScreenBlock(size, sourceX, sourceY);
        int tileX = (sourceX & 0xFF) / TILE_SIZE;
        int tileY = (sourceY & 0xFF) / TILE_SIZE;
        int mapEntry = memory.read16(screenBase + screenBlock * MAP_BLOCK_SIZE + (tileY * 32 + tileX) * 2);
        int tileNumber = mapEntry & 0x3FF;
        boolean horizontalFlip = (mapEntry & (1 << 10)) != 0;
        boolean verticalFlip = (mapEntry & (1 << 11)) != 0;
        int paletteBank = (mapEntry >>> 12) & 0xF;
        int pixelX = sourceX & 7;
        int pixelY = sourceY & 7;
        if (horizontalFlip) {
            pixelX = 7 - pixelX;
        }
        if (verticalFlip) {
            pixelY = 7 - pixelY;
        }

        int paletteIndex = eightBpp
                ? readTile8Bpp(memory, charBase, tileNumber, pixelX, pixelY)
                : readTile4Bpp(memory, charBase, tileNumber, paletteBank, pixelX, pixelY);
        if (paletteIndex == 0) {
            return TRANSPARENT;
        }
        return memory.read16(PALETTE + paletteIndex * 2) & 0x7FFF;
    }

    private void renderMode3Line(AddressSpace memory, int line, byte[] mask) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0) {
            return;
        }
        int source = VRAM + line * WIDTH * 2;
        int priority = bgControl(memory, 2) & 0x3;
        for (int x = 0; x < WIDTH; x++) {
            if (layerEnabled(mask, x, 2)) {
                submit(x, memory.read16(source + x * 2) & 0x7FFF, priority, 2, false);
            }
        }
    }

    private void renderMode4Line(AddressSpace memory, boolean backBuffer, int line, byte[] mask) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0) {
            return;
        }
        int source = VRAM + (backBuffer ? FRAME_1 : 0) + line * WIDTH;
        int priority = bgControl(memory, 2) & 0x3;
        for (int x = 0; x < WIDTH; x++) {
            if (!layerEnabled(mask, x, 2)) {
                continue;
            }
            int paletteIndex = memory.read8(source + x);
            if (paletteIndex != 0) {
                submit(x, memory.read16(PALETTE + paletteIndex * 2) & 0x7FFF, priority, 2, false);
            }
        }
    }

    private void renderMode5Line(AddressSpace memory, boolean backBuffer, int line, byte[] mask) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0 || line >= MODE_5_HEIGHT) {
            return;
        }
        int source = VRAM + (backBuffer ? FRAME_1 : 0) + line * MODE_5_WIDTH * 2;
        int priority = bgControl(memory, 2) & 0x3;
        for (int x = 0; x < MODE_5_WIDTH; x++) {
            if (layerEnabled(mask, x, 2)) {
                submit(x, memory.read16(source + x * 2) & 0x7FFF, priority, 2, false);
            }
        }
    }

    private void gatherObjectsLine(AddressSpace memory, int dispcnt, int line, byte[] mask) {
        boolean oneDimensionalMapping = (dispcnt & OBJ_1D_MAPPING) != 0;
        for (int object = 127; object >= 0; object--) {
            int base = OAM + object * 8;
            int attr0 = memory.read16(base);
            int attr1 = memory.read16(base + 2);
            int attr2 = memory.read16(base + 4);
            boolean affine = (attr0 & (1 << 8)) != 0;
            boolean doubleSize = affine && (attr0 & (1 << 9)) != 0;
            boolean disabled = !affine && (attr0 & (1 << 9)) != 0;
            int objectMode = (attr0 >>> 10) & 0x3;
            if (disabled || objectMode == OBJ_MODE_WINDOW) {
                continue;
            }

            int[] dimensions = objectDimensions((attr0 >>> 14) & 0x3, (attr1 >>> 14) & 0x3);
            int width = dimensions[0];
            int height = dimensions[1];
            int renderWidth = doubleSize ? width * 2 : width;
            int renderHeight = doubleSize ? height * 2 : height;
            int y = attr0 & 0xFF;
            if (y >= 160) {
                y -= 256;
            }
            int py = line - y;
            if (py < 0 || py >= renderHeight) {
                continue;
            }
            boolean semiTransparent = objectMode == OBJ_MODE_SEMI_TRANSPARENT;
            if (affine) {
                gatherAffineObjectLine(memory, oneDimensionalMapping, attr1, attr2, py,
                        width, height, renderWidth, renderHeight, eightBpp(attr0), semiTransparent, mask, false);
            } else {
                gatherObjectLine(memory, oneDimensionalMapping, attr0, attr1, attr2, py,
                        width, height, semiTransparent, mask, false);
            }
        }
    }

    private void gatherObjectLine(
            AddressSpace memory,
            boolean oneDimensionalMapping,
            int attr0,
            int attr1,
            int attr2,
            int py,
            int width,
            int height,
            boolean semiTransparent,
            byte[] mask,
            boolean windowSprite) {
        int x = attr1 & 0x1FF;
        if (x >= 256) {
            x -= 512;
        }
        if (x >= WIDTH || x + width <= 0) {
            return;
        }

        boolean eightBpp = eightBpp(attr0);
        boolean horizontalFlip = (attr1 & (1 << 12)) != 0;
        boolean verticalFlip = (attr1 & (1 << 13)) != 0;
        int tileNumber = attr2 & 0x3FF;
        int priority = (attr2 >>> 10) & 0x3;
        int paletteBank = (attr2 >>> 12) & 0xF;
        int tileY = verticalFlip ? height - 1 - py : py;

        for (int px = 0; px < width; px++) {
            int screenX = x + px;
            if (screenX < 0 || screenX >= WIDTH) {
                continue;
            }
            if (!windowSprite && !objectLayerEnabled(mask, screenX)) {
                continue;
            }
            int tileX = horizontalFlip ? width - 1 - px : px;
            int color = objectPixel(memory, oneDimensionalMapping, eightBpp, tileNumber, paletteBank, width, tileX, tileY);
            if (windowSprite) {
                if (color != TRANSPARENT) {
                    objWindowCoverage[screenX] = true;
                }
            } else {
                placeObjectPixel(screenX, color, priority, semiTransparent);
            }
        }
    }

    private void gatherAffineObjectLine(
            AddressSpace memory,
            boolean oneDimensionalMapping,
            int attr1,
            int attr2,
            int py,
            int textureWidth,
            int textureHeight,
            int renderWidth,
            int renderHeight,
            boolean eightBpp,
            boolean semiTransparent,
            byte[] mask,
            boolean windowSprite) {
        int x = attr1 & 0x1FF;
        if (x >= 256) {
            x -= 512;
        }
        if (x >= WIDTH || x + renderWidth <= 0) {
            return;
        }

        int matrix = (attr1 >>> 9) & 0x1F;
        int base = OAM + matrix * 32;
        int pa = signed16(memory.read16(base + 6));
        int pb = signed16(memory.read16(base + 14));
        int pc = signed16(memory.read16(base + 22));
        int pd = signed16(memory.read16(base + 30));
        int tileNumber = attr2 & 0x3FF;
        int priority = (attr2 >>> 10) & 0x3;
        int paletteBank = (attr2 >>> 12) & 0xF;
        int renderCenterX = renderWidth / 2;
        int renderCenterY = renderHeight / 2;
        int textureCenterX = textureWidth / 2;
        int textureCenterY = textureHeight / 2;
        int dy = py - renderCenterY;

        for (int px = 0; px < renderWidth; px++) {
            int screenX = x + px;
            if (screenX < 0 || screenX >= WIDTH) {
                continue;
            }
            if (!windowSprite && !objectLayerEnabled(mask, screenX)) {
                continue;
            }
            int dx = px - renderCenterX;
            int textureX = ((pa * dx + pb * dy) >> 8) + textureCenterX;
            int textureY = ((pc * dx + pd * dy) >> 8) + textureCenterY;
            if (textureX < 0 || textureX >= textureWidth || textureY < 0 || textureY >= textureHeight) {
                continue;
            }
            int color = objectPixel(memory, oneDimensionalMapping, eightBpp, tileNumber, paletteBank,
                    textureWidth, textureX, textureY);
            if (windowSprite) {
                if (color != TRANSPARENT) {
                    objWindowCoverage[screenX] = true;
                }
            } else {
                placeObjectPixel(screenX, color, priority, semiTransparent);
            }
        }
    }

    /// Keeps the front-most OBJ pixel per column (objects are scanned 127->0, so a lower
    /// index wins ties via {@code priority <= objPriority}).
    private void placeObjectPixel(int screenX, int color, int priority, boolean semiTransparent) {
        if (color == TRANSPARENT) {
            return;
        }
        if (objColor[screenX] == TRANSPARENT || priority <= objPriority[screenX]) {
            objColor[screenX] = color;
            objPriority[screenX] = priority;
            objSemi[screenX] = semiTransparent;
        }
    }

    private byte[] activeWindowMaskLine(AddressSpace memory, int dispcnt, int line) {
        int activeWindows = dispcnt & (WIN0_ENABLE | WIN1_ENABLE | OBJ_WINDOW_ENABLE);
        if (activeWindows == 0) {
            return null;
        }

        int winIn = memory.read16(WININ);
        int winOut = memory.read16(WINOUT);
        int outsideMask = winOut & (WINDOW_ALL_LAYERS | WINDOW_OBJ_ENABLE | WINDOW_BLEND_ENABLE);
        int win0Mask = winIn & (WINDOW_ALL_LAYERS | WINDOW_OBJ_ENABLE | WINDOW_BLEND_ENABLE);
        int win1Mask = (winIn >>> 8) & (WINDOW_ALL_LAYERS | WINDOW_OBJ_ENABLE | WINDOW_BLEND_ENABLE);
        int objWinMask = (winOut >>> WINOUT_OBJ_WINDOW_SHIFT) & (WINDOW_ALL_LAYERS | WINDOW_OBJ_ENABLE | WINDOW_BLEND_ENABLE);
        int win0H = memory.read16(WIN0H);
        int win1H = memory.read16(WIN1H);
        int win0V = memory.read16(WIN0V);
        int win1V = memory.read16(WIN1V);

        // Window priority (GBATEK): WIN0 > WIN1 > OBJ window > outside.
        for (int x = 0; x < WIDTH; x++) {
            int maskValue = outsideMask;
            if ((dispcnt & WIN0_ENABLE) != 0 && inWindow(x, line, win0H, win0V)) {
                maskValue = win0Mask;
            } else if ((dispcnt & WIN1_ENABLE) != 0 && inWindow(x, line, win1H, win1V)) {
                maskValue = win1Mask;
            } else if ((dispcnt & OBJ_WINDOW_ENABLE) != 0 && objWindowCoverage[x]) {
                maskValue = objWinMask;
            }
            windowMaskLine[x] = (byte) maskValue;
        }
        return windowMaskLine;
    }

    /// Preenche {@link #objWindowCoverage} com os pixels opacos de sprites em modo OBJ window
    /// (attr0 mode==2) desta scanline. Esses sprites nunca sao desenhados como pixel — servem so
    /// para definir a regiao do OBJ window consultada por {@link #activeWindowMaskLine}.
    private void computeObjWindowCoverage(AddressSpace memory, int dispcnt, int line) {
        Arrays.fill(objWindowCoverage, false);
        boolean oneDimensionalMapping = (dispcnt & OBJ_1D_MAPPING) != 0;
        for (int object = 0; object < 128; object++) {
            int base = OAM + object * 8;
            int attr0 = memory.read16(base);
            if (((attr0 >>> 10) & 0x3) != OBJ_MODE_WINDOW) {
                continue;
            }
            boolean affine = (attr0 & (1 << 8)) != 0;
            boolean doubleSize = affine && (attr0 & (1 << 9)) != 0;
            boolean disabled = !affine && (attr0 & (1 << 9)) != 0;
            if (disabled) {
                continue;
            }
            int attr1 = memory.read16(base + 2);
            int attr2 = memory.read16(base + 4);
            int[] dimensions = objectDimensions((attr0 >>> 14) & 0x3, (attr1 >>> 14) & 0x3);
            int width = dimensions[0];
            int height = dimensions[1];
            int renderWidth = doubleSize ? width * 2 : width;
            int renderHeight = doubleSize ? height * 2 : height;
            int y = attr0 & 0xFF;
            if (y >= 160) {
                y -= 256;
            }
            int py = line - y;
            if (py < 0 || py >= renderHeight) {
                continue;
            }
            if (affine) {
                gatherAffineObjectLine(memory, oneDimensionalMapping, attr1, attr2, py,
                        width, height, renderWidth, renderHeight, eightBpp(attr0), false, null, true);
            } else {
                gatherObjectLine(memory, oneDimensionalMapping, attr0, attr1, attr2, py,
                        width, height, false, null, true);
            }
        }
    }

    private static boolean inWindow(int x, int y, int horizontal, int vertical) {
        int left = (horizontal >>> 8) & 0xFF;
        int right = horizontal & 0xFF;
        int top = (vertical >>> 8) & 0xFF;
        int bottom = vertical & 0xFF;
        return inRangeWrapped(x, left, right, WIDTH) && inRangeWrapped(y, top, bottom, HEIGHT);
    }

    private static boolean inRangeWrapped(int value, int start, int end, int limit) {
        start = Math.min(start, limit);
        end = Math.min(end, limit);
        if (start <= end) {
            return value >= start && value < end;
        }
        return value >= start || value < end;
    }

    private static boolean layerEnabled(byte[] mask, int screenX, int layer) {
        return mask == null || (mask[screenX] & (1 << layer)) != 0;
    }

    private static boolean objectLayerEnabled(byte[] mask, int screenX) {
        return mask == null || (mask[screenX] & WINDOW_OBJ_ENABLE) != 0;
    }

    private static boolean eightBpp(int attr0) {
        return (attr0 & (1 << 13)) != 0;
    }

    private static int objectPixel(
            AddressSpace memory,
            boolean oneDimensionalMapping,
            boolean eightBpp,
            int tileNumber,
            int paletteBank,
            int width,
            int pixelX,
            int pixelY) {
        int tileX = pixelX / TILE_SIZE;
        int tileY = pixelY / TILE_SIZE;
        int bppTileStride = eightBpp ? 2 : 1;
        int tile = oneDimensionalMapping
                ? tileNumber + (tileY * (width / TILE_SIZE) + tileX) * bppTileStride
                : tileNumber + tileY * 32 + tileX * bppTileStride;
        int localX = pixelX & 7;
        int localY = pixelY & 7;
        int paletteIndex = eightBpp
                ? readObjectTile8Bpp(memory, tile, localX, localY)
                : readTile4Bpp(memory, OBJ_TILES, tile, paletteBank, localX, localY);
        if (paletteIndex == 0) {
            return TRANSPARENT;
        }
        return memory.read16(OBJ_PALETTE + paletteIndex * 2) & 0x7FFF;
    }

    private static int signed16(int value) {
        return (short) (value & 0xFFFF);
    }

    public static int bgr555ToArgb(int value) {
        int red5 = value & 0x1F;
        int green5 = (value >>> 5) & 0x1F;
        int blue5 = (value >>> 10) & 0x1F;
        int red8 = (red5 << 3) | (red5 >>> 2);
        int green8 = (green5 << 3) | (green5 >>> 2);
        int blue8 = (blue5 << 3) | (blue5 >>> 2);
        return 0xFF000000 | (red8 << 16) | (green8 << 8) | blue8;
    }

    private static int bgControl(AddressSpace memory, int bg) {
        return memory.read16(BG0CNT + bg * 2);
    }

    private static int readTile4Bpp(
            AddressSpace memory,
            int charBase,
            int tileNumber,
            int paletteBank,
            int pixelX,
            int pixelY) {
        int byteValue = memory.read8(charBase + tileNumber * 32 + pixelY * 4 + pixelX / 2);
        int colorIndex = (pixelX & 1) == 0 ? byteValue & 0xF : (byteValue >>> 4) & 0xF;
        return colorIndex == 0 ? 0 : paletteBank * 16 + colorIndex;
    }

    private static int readTile8Bpp(AddressSpace memory, int charBase, int tileNumber, int pixelX, int pixelY) {
        return memory.read8(charBase + tileNumber * 64 + pixelY * 8 + pixelX);
    }

    private static int readObjectTile8Bpp(AddressSpace memory, int tileNumber, int pixelX, int pixelY) {
        return memory.read8(OBJ_TILES + tileNumber * 32 + pixelY * 8 + pixelX);
    }

    private static int regularBackgroundWidth(int size) {
        return (size & 1) == 0 ? 256 : 512;
    }

    private static int regularBackgroundHeight(int size) {
        return (size & 2) == 0 ? 256 : 512;
    }

    private static int affineBackgroundSize(int size) {
        return 128 << (size & 0x3);
    }

    private static int regularScreenBlock(int size, int sourceX, int sourceY) {
        return switch (size) {
            case 0 -> 0;
            case 1 -> sourceX >= 256 ? 1 : 0;
            case 2 -> sourceY >= 256 ? 1 : 0;
            case 3 -> (sourceX >= 256 ? 1 : 0) + (sourceY >= 256 ? 2 : 0);
            default -> throw new IllegalArgumentException("Invalid regular BG size: " + size);
        };
    }

    private static int[] objectDimensions(int shape, int size) {
        return switch (shape) {
            case 0 -> switch (size) {
                case 0 -> new int[]{8, 8};
                case 1 -> new int[]{16, 16};
                case 2 -> new int[]{32, 32};
                case 3 -> new int[]{64, 64};
                default -> throw new IllegalArgumentException("Invalid OBJ size: " + size);
            };
            case 1 -> switch (size) {
                case 0 -> new int[]{16, 8};
                case 1 -> new int[]{32, 8};
                case 2 -> new int[]{32, 16};
                case 3 -> new int[]{64, 32};
                default -> throw new IllegalArgumentException("Invalid OBJ size: " + size);
            };
            case 2 -> switch (size) {
                case 0 -> new int[]{8, 16};
                case 1 -> new int[]{8, 32};
                case 2 -> new int[]{16, 32};
                case 3 -> new int[]{32, 64};
                default -> throw new IllegalArgumentException("Invalid OBJ size: " + size);
            };
            default -> new int[]{0, 0};
        };
    }

    private static int signed28(int value) {
        int masked = value & 0x0FFF_FFFF;
        return (masked << 4) >> 4;
    }
}
