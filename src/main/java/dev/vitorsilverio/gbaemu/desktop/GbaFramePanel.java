package dev.vitorsilverio.gbaemu.desktop;

import dev.vitorsilverio.gbaemu.video.GbaVideo;

import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/// Painel Swing que desenha o framebuffer do GBA com escala inteira.
public final class GbaFramePanel extends JPanel {
    private final int scale;
    private BufferedImage image;

    public GbaFramePanel(int scale) {
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be positive");
        }
        this.scale = scale;
        setPreferredSize(new Dimension(GbaVideo.WIDTH * scale, GbaVideo.HEIGHT * scale));
    }

    public void setFrame(int[] argb) {
        image = GbaFrameImage.fromArgb(argb, GbaVideo.WIDTH, GbaVideo.HEIGHT);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (image == null) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, 0, 0, GbaVideo.WIDTH * scale, GbaVideo.HEIGHT * scale, null);
        } finally {
            g.dispose();
        }
    }
}
