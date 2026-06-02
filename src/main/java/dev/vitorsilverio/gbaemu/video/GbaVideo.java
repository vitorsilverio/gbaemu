package dev.vitorsilverio.gbaemu.video;

import dev.vitorsilverio.armjitter.memory.AddressSpace;

import java.util.Arrays;

/// Renderer inicial da PPU do GBA.
///
/// Nesta fase ele cobre modos bitmap, backgrounds regulares e sprites simples,
/// criando um framebuffer observavel durante o bring-up da BIOS.
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
    private static final int FORCED_BLANK = 1 << 7;
    private static final int BG_ENABLE_SHIFT = 8;
    private static final int BG2_ENABLE = 1 << 10;
    private static final int OBJ_ENABLE = 1 << 12;
    private static final int OBJ_1D_MAPPING = 1 << 6;
    private static final int WIN0_ENABLE = 1 << 13;
    private static final int WIN1_ENABLE = 1 << 14;
    private static final int OBJ_WINDOW_ENABLE = 1 << 15;
    private static final int WINDOW_OBJ_ENABLE = 1 << 4;
    private static final int WINDOW_ALL_LAYERS = 0x1F;
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
    private static final int TRANSPARENT = 0;

    private final int[] framebuffer = new int[WIDTH * HEIGHT];
    private final int[] priorities = new int[WIDTH * HEIGHT];
    private final byte[] windowMasks = new byte[WIDTH * HEIGHT];

    public int[] renderFrame(AddressSpace memory) {
        int dispcnt = memory.read16(DISPCNT);
        int mode = dispcnt & 0x7;

        if ((dispcnt & FORCED_BLANK) != 0) {
            Arrays.fill(framebuffer, 0xFFFFFFFF);
            return framebufferSnapshot();
        }
        Arrays.fill(framebuffer, bgr555ToArgb(memory.read16(PALETTE)));
        Arrays.fill(priorities, 4);
        byte[] layerMasks = activeWindowMasks(memory, dispcnt);

        switch (mode) {
            case 0 -> renderTileBackgrounds(memory, dispcnt, 0b1111, 0, layerMasks);
            case 1 -> renderTileBackgrounds(memory, dispcnt, 0b0011, 0b0100, layerMasks);
            case 2 -> renderTileBackgrounds(memory, dispcnt, 0, 0b1100, layerMasks);
            case 3 -> renderMode3(memory, layerMasks);
            case 4 -> renderMode4(memory, (dispcnt & (1 << 4)) != 0, layerMasks);
            case 5 -> renderMode5(memory, (dispcnt & (1 << 4)) != 0, layerMasks);
            default -> {
            }
        }
        if ((dispcnt & OBJ_ENABLE) != 0) {
            renderObjects(memory, dispcnt, layerMasks);
        }

        return framebufferSnapshot();
    }

    public int[] framebufferSnapshot() {
        return Arrays.copyOf(framebuffer, framebuffer.length);
    }

    private void renderTileBackgrounds(
            AddressSpace memory,
            int dispcnt,
            int regularMask,
            int affineMask,
            byte[] layerMasks) {
        for (int priority = 3; priority >= 0; priority--) {
            for (int bg = 3; bg >= 0; bg--) {
                if ((dispcnt & (1 << (BG_ENABLE_SHIFT + bg))) == 0) {
                    continue;
                }
                int bgcnt = bgControl(memory, bg);
                if ((bgcnt & 0x3) != priority) {
                    continue;
                }
                if ((regularMask & (1 << bg)) != 0) {
                    renderRegularBackground(memory, bg, bgcnt, layerMasks);
                } else if ((affineMask & (1 << bg)) != 0) {
                    renderAffineBackground(memory, bg, bgcnt, layerMasks);
                }
            }
        }
    }

    private void renderRegularBackground(AddressSpace memory, int bg, int bgcnt, byte[] layerMasks) {
        for (int y = 0; y < HEIGHT; y++) {
            renderRegularBackgroundLine(memory, bg, bgcnt, y, layerMasks);
        }
    }

    private void renderRegularBackgroundLine(
            AddressSpace memory,
            int bg,
            int bgcnt,
            int screenY,
            byte[] layerMasks) {
        int charBase = VRAM + ((bgcnt >>> 2) & 0x3) * CHAR_BLOCK_SIZE;
        boolean eightBpp = (bgcnt & (1 << 7)) != 0;
        int screenBase = VRAM + ((bgcnt >>> 8) & 0x1F) * MAP_BLOCK_SIZE;
        int size = (bgcnt >>> 14) & 0x3;
        int bgWidth = regularBackgroundWidth(size);
        int bgHeight = regularBackgroundHeight(size);
        int hofs = memory.read16(BG0HOFS + bg * 4) & 0x1FF;
        int vofs = memory.read16(BG0VOFS + bg * 4) & 0x1FF;
        int sourceY = Math.floorMod(screenY + vofs, bgHeight);

        for (int screenX = 0; screenX < WIDTH; screenX++) {
            int index = screenY * WIDTH + screenX;
            if (!layerEnabled(layerMasks, index, bg)) {
                continue;
            }
            int sourceX = Math.floorMod(screenX + hofs, bgWidth);
            int color = regularBackgroundPixel(memory, charBase, screenBase, size, eightBpp, sourceX, sourceY);
            if (color != TRANSPARENT) {
                framebuffer[index] = color;
                priorities[index] = bgcnt & 0x3;
            }
        }
    }

    private void renderAffineBackground(AddressSpace memory, int bg, int bgcnt, byte[] layerMasks) {
        int charBase = VRAM + ((bgcnt >>> 2) & 0x3) * CHAR_BLOCK_SIZE;
        int screenBase = VRAM + ((bgcnt >>> 8) & 0x1F) * MAP_BLOCK_SIZE;
        boolean wrap = (bgcnt & (1 << 13)) != 0;
        int size = affineBackgroundSize((bgcnt >>> 14) & 0x3);
        int registerBase = bg == 2 ? BG2PA : BG3PA;
        int pa = signed16(memory.read16(registerBase));
        int pb = signed16(memory.read16(registerBase + 2));
        int pc = signed16(memory.read16(registerBase + 4));
        int pd = signed16(memory.read16(registerBase + 6));
        int refX = signed28(memory.read32(bg == 2 ? BG2X : BG3X));
        int refY = signed28(memory.read32(bg == 2 ? BG2Y : BG3Y));

        for (int screenY = 0; screenY < HEIGHT; screenY++) {
            int sourceX = refX + pb * screenY;
            int sourceY = refY + pd * screenY;
            for (int screenX = 0; screenX < WIDTH; screenX++) {
                int index = screenY * WIDTH + screenX;
                int pixelX = sourceX >> 8;
                int pixelY = sourceY >> 8;
                if (layerEnabled(layerMasks, index, bg)) {
                    int color = affineBackgroundPixel(memory, charBase, screenBase, size, wrap, pixelX, pixelY);
                    if (color != TRANSPARENT) {
                        framebuffer[index] = color;
                        priorities[index] = bgcnt & 0x3;
                    }
                }
                sourceX += pa;
                sourceY += pc;
            }
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
        return bgr555ToArgb(memory.read16(PALETTE + paletteIndex * 2));
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
        return bgr555ToArgb(memory.read16(PALETTE + paletteIndex * 2));
    }

    private void renderMode3(AddressSpace memory, byte[] layerMasks) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0) {
            return;
        }
        for (int y = 0; y < HEIGHT; y++) {
            int line = y * WIDTH;
            int source = VRAM + line * 2;
            for (int x = 0; x < WIDTH; x++) {
                int index = line + x;
                if (!layerEnabled(layerMasks, index, 2)) {
                    continue;
                }
                framebuffer[index] = bgr555ToArgb(memory.read16(source + x * 2));
                priorities[index] = 2;
            }
        }
    }

    private void renderMode4(AddressSpace memory, boolean backBuffer, byte[] layerMasks) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0) {
            return;
        }
        int base = VRAM + (backBuffer ? FRAME_1 : 0);
        for (int y = 0; y < HEIGHT; y++) {
            int line = y * WIDTH;
            int source = base + line;
            for (int x = 0; x < WIDTH; x++) {
                int index = line + x;
                if (!layerEnabled(layerMasks, index, 2)) {
                    continue;
                }
                int paletteIndex = memory.read8(source + x);
                if (paletteIndex != 0) {
                    framebuffer[index] = bgr555ToArgb(memory.read16(PALETTE + paletteIndex * 2));
                    priorities[index] = 2;
                }
            }
        }
    }

    private void renderMode5(AddressSpace memory, boolean backBuffer, byte[] layerMasks) {
        if ((memory.read16(DISPCNT) & BG2_ENABLE) == 0) {
            return;
        }
        int base = VRAM + (backBuffer ? FRAME_1 : 0);
        for (int y = 0; y < MODE_5_HEIGHT; y++) {
            int line = y * WIDTH;
            int source = base + y * MODE_5_WIDTH * 2;
            for (int x = 0; x < MODE_5_WIDTH; x++) {
                int index = line + x;
                if (!layerEnabled(layerMasks, index, 2)) {
                    continue;
                }
                framebuffer[index] = bgr555ToArgb(memory.read16(source + x * 2));
                priorities[index] = 2;
            }
        }
    }

    private void renderObjects(AddressSpace memory, int dispcnt, byte[] layerMasks) {
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
            if (disabled || objectMode == 2) {
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
            if (y >= HEIGHT || y + renderHeight <= 0) {
                continue;
            }
            if (affine) {
                renderAffineObject(
                        memory,
                        oneDimensionalMapping,
                        attr0,
                        attr1,
                        attr2,
                        y,
                        width,
                        height,
                        renderWidth,
                        renderHeight,
                        layerMasks);
            } else {
                renderObject(memory, oneDimensionalMapping, attr0, attr1, attr2, y, width, height, layerMasks);
            }
        }
    }

    private void renderObject(
            AddressSpace memory,
            boolean oneDimensionalMapping,
            int attr0,
            int attr1,
            int attr2,
            int y,
            int width,
            int height,
            byte[] layerMasks) {
        int x = attr1 & 0x1FF;
        if (x >= 256) {
            x -= 512;
        }
        if (x >= WIDTH || x + width <= 0) {
            return;
        }

        boolean eightBpp = (attr0 & (1 << 13)) != 0;
        boolean horizontalFlip = (attr1 & (1 << 12)) != 0;
        boolean verticalFlip = (attr1 & (1 << 13)) != 0;
        int tileNumber = attr2 & 0x3FF;
        int priority = (attr2 >>> 10) & 0x3;
        int paletteBank = (attr2 >>> 12) & 0xF;

        for (int py = 0; py < height; py++) {
            int screenY = y + py;
            if (screenY < 0 || screenY >= HEIGHT) {
                continue;
            }
            int tileY = verticalFlip ? height - 1 - py : py;
            for (int px = 0; px < width; px++) {
                int screenX = x + px;
                if (screenX < 0 || screenX >= WIDTH) {
                    continue;
                }
                int index = screenY * WIDTH + screenX;
                if (!objectLayerEnabled(layerMasks, index)) {
                    continue;
                }
                int tileX = horizontalFlip ? width - 1 - px : px;
                int color = objectPixel(memory, oneDimensionalMapping, eightBpp, tileNumber, paletteBank, width, tileX, tileY);
                if (color == TRANSPARENT) {
                    continue;
                }
                if (priority <= priorities[index]) {
                    framebuffer[index] = color;
                    priorities[index] = priority;
                }
            }
        }
    }

    private void renderAffineObject(
            AddressSpace memory,
            boolean oneDimensionalMapping,
            int attr0,
            int attr1,
            int attr2,
            int y,
            int textureWidth,
            int textureHeight,
            int renderWidth,
            int renderHeight,
            byte[] layerMasks) {
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
        boolean eightBpp = (attr0 & (1 << 13)) != 0;
        int tileNumber = attr2 & 0x3FF;
        int priority = (attr2 >>> 10) & 0x3;
        int paletteBank = (attr2 >>> 12) & 0xF;
        int renderCenterX = renderWidth / 2;
        int renderCenterY = renderHeight / 2;
        int textureCenterX = textureWidth / 2;
        int textureCenterY = textureHeight / 2;

        for (int py = 0; py < renderHeight; py++) {
            int screenY = y + py;
            if (screenY < 0 || screenY >= HEIGHT) {
                continue;
            }
            int dy = py - renderCenterY;
            for (int px = 0; px < renderWidth; px++) {
                int screenX = x + px;
                if (screenX < 0 || screenX >= WIDTH) {
                    continue;
                }
                int index = screenY * WIDTH + screenX;
                if (!objectLayerEnabled(layerMasks, index)) {
                    continue;
                }

                int dx = px - renderCenterX;
                int textureX = ((pa * dx + pb * dy) >> 8) + textureCenterX;
                int textureY = ((pc * dx + pd * dy) >> 8) + textureCenterY;
                if (textureX < 0 || textureX >= textureWidth || textureY < 0 || textureY >= textureHeight) {
                    continue;
                }

                int color = objectPixel(
                        memory,
                        oneDimensionalMapping,
                        eightBpp,
                        tileNumber,
                        paletteBank,
                        textureWidth,
                        textureX,
                        textureY);
                if (color == TRANSPARENT) {
                    continue;
                }
                if (priority <= priorities[index]) {
                    framebuffer[index] = color;
                    priorities[index] = priority;
                }
            }
        }
    }

    private byte[] activeWindowMasks(AddressSpace memory, int dispcnt) {
        int activeWindows = dispcnt & (WIN0_ENABLE | WIN1_ENABLE | OBJ_WINDOW_ENABLE);
        if (activeWindows == 0) {
            return null;
        }

        int winIn = memory.read16(WININ);
        int winOut = memory.read16(WINOUT);
        int outsideMask = winOut & WINDOW_ALL_LAYERS;
        int win0Mask = winIn & WINDOW_ALL_LAYERS;
        int win1Mask = (winIn >>> 8) & WINDOW_ALL_LAYERS;
        int win0H = memory.read16(WIN0H);
        int win1H = memory.read16(WIN1H);
        int win0V = memory.read16(WIN0V);
        int win1V = memory.read16(WIN1V);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int mask = outsideMask;
                if ((dispcnt & WIN0_ENABLE) != 0 && inWindow(x, y, win0H, win0V)) {
                    mask = win0Mask;
                } else if ((dispcnt & WIN1_ENABLE) != 0 && inWindow(x, y, win1H, win1V)) {
                    mask = win1Mask;
                }
                windowMasks[y * WIDTH + x] = (byte) mask;
            }
        }
        return windowMasks;
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

    private static boolean layerEnabled(byte[] layerMasks, int index, int layer) {
        return layerMasks == null || (layerMasks[index] & (1 << layer)) != 0;
    }

    private static boolean objectLayerEnabled(byte[] layerMasks, int index) {
        return layerMasks == null || (layerMasks[index] & WINDOW_OBJ_ENABLE) != 0;
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
        return bgr555ToArgb(memory.read16(OBJ_PALETTE + paletteIndex * 2));
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
        return colorIndex == 0 ? TRANSPARENT : paletteBank * 16 + colorIndex;
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
