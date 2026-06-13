package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.core.GbaConsole;
import dev.vitorsilverio.gbaemu.memory.GbaBus;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/// Live, tabbed view of the PPU: the raw registers, every background layer rendered as
/// its full map, the character-block tile sheets, the OAM sprites (with a decoded
/// preview) and the palette. Decodes VRAM on demand via {@link GbaPpuDebug}; only the
/// visible tab is refreshed so big maps don't cost anything while hidden.
public final class PpuDebugWindow {
    private static final int OBJECT_COUNT = 128;
    private static final int CHAR_BLOCKS = 6;

    private final Supplier<GbaConsole> consoleSupplier;
    private final JFrame frame = new JFrame("PPU / video");
    private final JTabbedPane tabs = new JTabbedPane();

    private final JTextArea registers = new JTextArea(28, 60);

    private final ImagePanel[] backgrounds = new ImagePanel[4];
    private final JLabel[] backgroundLabels = new JLabel[4];

    private final ImagePanel[] charBlocks = new ImagePanel[CHAR_BLOCKS];
    private final JSpinner paletteBank = new JSpinner(new SpinnerNumberModel(0, 0, 15, 1));
    private final JCheckBox tilesEightBpp = new JCheckBox("256-color (8bpp)");

    private final DefaultTableModel oamModel = new DefaultTableModel(
            new Object[]{"#", "X", "Y", "Size", "Tile", "Pal", "Bpp", "Aff", "Mode", "Pr", "On"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable oamTable = new JTable(oamModel);
    private final ImagePanel spritePreview = new ImagePanel(4);
    private final JTextArea spriteInfo = new JTextArea(4, 24);

    private final PalettePanel palette = new PalettePanel();

    private final Timer refreshTimer = new Timer(600, event -> refresh());

    public PpuDebugWindow(Supplier<GbaConsole> consoleSupplier) {
        this.consoleSupplier = consoleSupplier;
        registers.setEditable(false);
        registers.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        spriteInfo.setEditable(false);
        spriteInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        tabs.addTab("Registers", new JScrollPane(registers));
        tabs.addTab("Backgrounds", buildBackgroundsTab());
        tabs.addTab("Tiles", buildTilesTab());
        tabs.addTab("Sprites", buildSpritesTab());
        tabs.addTab("Palette", buildPaletteTab());
        tabs.addChangeListener(event -> refresh());

        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setContentPane(tabs);
        frame.setMinimumSize(new Dimension(900, 640));
        frame.pack();
        frame.setLocationRelativeTo(null);
        refresh();
        frame.setVisible(true);
        refreshTimer.start();
    }

    /// Re-shows and re-focuses an already-created window.
    public void present() {
        frame.setVisible(true);
        frame.toFront();
    }

    private JComponent buildBackgroundsTab() {
        JPanel grid = new JPanel(new GridLayout(2, 2, 6, 6));
        for (int bg = 0; bg < 4; bg++) {
            backgrounds[bg] = new ImagePanel(1);
            backgroundLabels[bg] = new JLabel("BG" + bg);
            JPanel cell = new JPanel(new BorderLayout(0, 2));
            cell.add(backgroundLabels[bg], BorderLayout.NORTH);
            cell.add(new JScrollPane(backgrounds[bg]), BorderLayout.CENTER);
            grid.add(cell);
        }
        return grid;
    }

    private JComponent buildTilesTab() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("4bpp palette bank:"));
        controls.add(paletteBank);
        controls.add(tilesEightBpp);
        paletteBank.addChangeListener(event -> refreshTilesIfVisible());
        tilesEightBpp.addActionListener(event -> refreshTilesIfVisible());

        JPanel grid = new JPanel(new GridLayout(3, 2, 8, 8));
        for (int block = 0; block < CHAR_BLOCKS; block++) {
            charBlocks[block] = new ImagePanel(2);
            JPanel cell = new JPanel(new BorderLayout(0, 2));
            cell.add(new JLabel(block < 4 ? "BG char block " + block : "OBJ tile block " + (block - 4)),
                    BorderLayout.NORTH);
            cell.add(charBlocks[block], BorderLayout.CENTER);
            grid.add(cell);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(new JScrollPane(grid), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildSpritesTab() {
        oamTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        oamTable.setAutoCreateRowSorter(false);
        oamTable.getSelectionModel().addListSelectionListener(event -> refreshSpritePreview());
        for (int i = 0; i < OBJECT_COUNT; i++) {
            oamModel.addRow(new Object[]{i, "", "", "", "", "", "", "", "", "", ""});
        }

        JPanel right = new JPanel(new BorderLayout(0, 4));
        spritePreview.setPreferredSize(new Dimension(280, 280));
        right.add(new JLabel("Selected sprite"), BorderLayout.NORTH);
        right.add(spritePreview, BorderLayout.CENTER);
        right.add(new JScrollPane(spriteInfo), BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JScrollPane(oamTable), BorderLayout.CENTER);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    private JComponent buildPaletteTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(palette), BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        GbaConsole console = consoleSupplier.get();
        if (console == null) {
            registers.setText("No ROM loaded.");
            return;
        }
        switch (tabs.getSelectedIndex()) {
            case 0 -> registers.setText(dumpRegisters(console.bus()));
            case 1 -> refreshBackgrounds(console.bus());
            case 2 -> refreshTiles(console.bus());
            case 3 -> refreshSprites(console.bus());
            case 4 -> {
                palette.setConsole(console);
                palette.repaint();
            }
            default -> {
            }
        }
    }

    private void refreshTilesIfVisible() {
        GbaConsole console = consoleSupplier.get();
        if (console != null && tabs.getSelectedIndex() == 2) {
            refreshTiles(console.bus());
        }
    }

    private void refreshBackgrounds(GbaBus bus) {
        int dispcnt = bus.read16(0x04000000);
        for (int bg = 0; bg < 4; bg++) {
            GbaPpuDebug.DebugImage image = GbaPpuDebug.backgroundLayer(bus, bg);
            backgrounds[bg].setDebugImage(image);
            int cnt = bus.read16(0x04000008 + bg * 2);
            boolean on = (dispcnt & (1 << (8 + bg))) != 0;
            String size = image == null
                    ? "(unused in mode " + (dispcnt & 7) + ")"
                    : image.width() + "x" + image.height();
            backgroundLabels[bg].setText(String.format(
                    "BG%d %s prio=%d charBlk=%d scrBlk=%d %s  %s",
                    bg, on ? "ON" : "off", cnt & 3, (cnt >> 2) & 3, (cnt >> 8) & 0x1F,
                    (cnt & 0x80) != 0 ? "8bpp" : "4bpp", size));
        }
    }

    private void refreshTiles(GbaBus bus) {
        int bank = (Integer) paletteBank.getValue();
        boolean eightBpp = tilesEightBpp.isSelected();
        for (int block = 0; block < CHAR_BLOCKS; block++) {
            charBlocks[block].setDebugImage(GbaPpuDebug.charBlock(bus, block, bank, eightBpp));
        }
    }

    private void refreshSprites(GbaBus bus) {
        for (int i = 0; i < OBJECT_COUNT; i++) {
            int a0 = bus.read16(0x07000000 + i * 8);
            int a1 = bus.read16(0x07000000 + i * 8 + 2);
            int a2 = bus.read16(0x07000000 + i * 8 + 4);
            boolean affine = (a0 & (1 << 8)) != 0;
            boolean disabled = !affine && (a0 & (1 << 9)) != 0;
            int y = a0 & 0xFF;
            if (y >= 160) y -= 256;
            int x = a1 & 0x1FF;
            if (x >= 256) x -= 512;
            int[] wh = objectDimensions((a0 >> 14) & 3, (a1 >> 14) & 3);
            oamModel.setValueAt(x, i, 1);
            oamModel.setValueAt(y, i, 2);
            oamModel.setValueAt(wh[0] + "x" + wh[1], i, 3);
            oamModel.setValueAt(String.format("%03X", a2 & 0x3FF), i, 4);
            oamModel.setValueAt((a2 >> 12) & 0xF, i, 5);
            oamModel.setValueAt((a0 & 0x2000) != 0 ? 8 : 4, i, 6);
            oamModel.setValueAt(affine ? "Y" : "", i, 7);
            oamModel.setValueAt((a0 >> 10) & 3, i, 8);
            oamModel.setValueAt((a2 >> 10) & 3, i, 9);
            oamModel.setValueAt(disabled ? "" : "Y", i, 10);
        }
        refreshSpritePreview();
    }

    private void refreshSpritePreview() {
        GbaConsole console = consoleSupplier.get();
        int row = oamTable.getSelectedRow();
        if (console == null || row < 0 || row >= OBJECT_COUNT) {
            spritePreview.setDebugImage(null);
            spriteInfo.setText("Select a sprite in the list.");
            return;
        }
        GbaBus bus = console.bus();
        GbaPpuDebug.DebugImage image = GbaPpuDebug.spriteImage(bus, row);
        spritePreview.setDebugImage(image);
        int a0 = bus.read16(0x07000000 + row * 8);
        int a1 = bus.read16(0x07000000 + row * 8 + 2);
        int a2 = bus.read16(0x07000000 + row * 8 + 4);
        spriteInfo.setText(String.format(
                "OBJ %d%n%dx%d  tile=0x%03X  pal=%d%n%s, %s%nattr0=%04X attr1=%04X attr2=%04X",
                row, image.width(), image.height(), a2 & 0x3FF, (a2 >> 12) & 0xF,
                (a0 & 0x2000) != 0 ? "8bpp" : "4bpp",
                (bus.read16(0x04000000) & (1 << 6)) != 0 ? "1D map" : "2D map",
                a0, a1, a2));
    }

    private static int[] objectDimensions(int shape, int size) {
        int[][][] table = {
                {{8, 8}, {16, 16}, {32, 32}, {64, 64}},
                {{16, 8}, {32, 8}, {32, 16}, {64, 32}},
                {{8, 16}, {8, 32}, {16, 32}, {32, 64}},
                {{8, 8}, {8, 8}, {8, 8}, {8, 8}}
        };
        return table[shape][size];
    }

    private static String dumpRegisters(GbaBus bus) {
        StringBuilder out = new StringBuilder();
        int dispcnt = bus.read16(0x04000000);
        out.append(String.format("DISPCNT=%04X mode=%d obj=%b 1Dmap=%b win0=%b win1=%b objwin=%b forceBlank=%b%n",
                dispcnt, dispcnt & 7, (dispcnt & (1 << 12)) != 0, (dispcnt & (1 << 6)) != 0,
                (dispcnt & (1 << 13)) != 0, (dispcnt & (1 << 14)) != 0, (dispcnt & (1 << 15)) != 0,
                (dispcnt & (1 << 7)) != 0));
        out.append(String.format("DISPSTAT=%04X  VCOUNT=%d%n", bus.read16(0x04000004), bus.read16(0x04000006)));
        out.append(String.format("BLDCNT=%04X BLDALPHA=%04X BLDY=%04X%n",
                bus.read16(0x04000050), bus.read16(0x04000052), bus.read16(0x04000054)));
        out.append(String.format("WIN0H=%04X WIN1H=%04X WIN0V=%04X WIN1V=%04X WININ=%04X WINOUT=%04X%n",
                bus.read16(0x04000040), bus.read16(0x04000042), bus.read16(0x04000044),
                bus.read16(0x04000046), bus.read16(0x04000048), bus.read16(0x0400004A)));
        for (int bg = 0; bg < 4; bg++) {
            int cnt = bus.read16(0x04000008 + bg * 2);
            out.append(String.format("BG%d %s cnt=%04X prio=%d charBlk=%d scrBlk=%d 8bpp=%b size=%d hofs=%d vofs=%d%n",
                    bg, (dispcnt & (1 << (8 + bg))) != 0 ? "ON " : "off", cnt, cnt & 3, (cnt >> 2) & 3,
                    (cnt >> 8) & 0x1F, (cnt & 0x80) != 0, (cnt >> 14) & 3,
                    bus.read16(0x04000010 + bg * 4) & 0x1FF, bus.read16(0x04000012 + bg * 4) & 0x1FF));
        }
        out.append(String.format("BG2aff PA=%04X PB=%04X PC=%04X PD=%04X X=%08X Y=%08X%n",
                bus.read16(0x04000020), bus.read16(0x04000022), bus.read16(0x04000024),
                bus.read16(0x04000026), bus.read32(0x04000028), bus.read32(0x0400002C)));
        out.append(String.format("BG3aff PA=%04X PB=%04X PC=%04X PD=%04X X=%08X Y=%08X%n",
                bus.read16(0x04000030), bus.read16(0x04000032), bus.read16(0x04000034),
                bus.read16(0x04000036), bus.read32(0x04000038), bus.read32(0x0400003C)));
        return out.toString();
    }

    /// Displays a {@link GbaPpuDebug.DebugImage} at an integer scale with nearest-neighbour
    /// sampling, sized to the image so it scrolls inside its viewport.
    private static final class ImagePanel extends JPanel {
        private final int scale;
        private BufferedImage image;

        ImagePanel(int scale) {
            this.scale = scale;
            setBackground(new Color(48, 48, 48));
        }

        void setDebugImage(GbaPpuDebug.DebugImage debugImage) {
            image = debugImage == null
                    ? null
                    : GbaFrameImage.fromArgb(debugImage.argb(), debugImage.width(), debugImage.height());
            if (image != null) {
                setPreferredSize(new Dimension(image.getWidth() * scale, image.getHeight() * scale));
            }
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, 0, 0, image.getWidth() * scale, image.getHeight() * scale, null);
            } finally {
                g2.dispose();
            }
        }
    }

    /// The 512 palette entries (256 BG at 0x05000000, 256 OBJ at 0x05000200) as a 32x16
    /// grid of swatches.
    private static final class PalettePanel extends JPanel {
        private static final int CELL = 14;
        private static final int COLUMNS = 32;
        private GbaConsole console;

        PalettePanel() {
            setBorder(BorderFactory.createTitledBorder("Palette (top 8 rows: BG  bottom 8 rows: OBJ)"));
            setPreferredSize(new Dimension(COLUMNS * CELL + 12, 16 * CELL + 28));
        }

        void setConsole(GbaConsole console) {
            this.console = console;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (console == null) {
                return;
            }
            GbaBus bus = console.bus();
            java.awt.Insets insets = getInsets();
            for (int index = 0; index < 512; index++) {
                int color = GbaPpuDebug.bgr555ToArgb(bus.read16(0x05000000 + index * 2));
                int column = index % COLUMNS;
                int rowGroup = index / 256;
                int row = (index % 256) / COLUMNS;
                graphics.setColor(new Color(color));
                graphics.fillRect(insets.left + column * CELL, insets.top + (row + rowGroup * 8) * CELL,
                        CELL - 1, CELL - 1);
            }
        }
    }
}
