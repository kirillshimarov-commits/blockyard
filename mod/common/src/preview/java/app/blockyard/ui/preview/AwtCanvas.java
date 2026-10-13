package app.blockyard.ui.preview;

import app.blockyard.ui.core.Canvas;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Raster;
import app.blockyard.ui.core.SpaceArt;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Offline canvas: draws the same rasterized pieces the game uses into a PNG-ready image. */
public final class AwtCanvas implements Canvas {
    final BufferedImage out;
    final Graphics2D g;
    final float ppu;
    final float w;

    AwtCanvas(int pxW, int pxH) {
        this(pxW, pxH, pxH / 270f);
    }

    /** One unit = unitPx pixels (vanilla GUI units at a GUI scale). */
    AwtCanvas(int pxW, int pxH, float unitPx) {
        out = new BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB);
        g = Raster.quality(out);
        ppu = unitPx;
        w = pxW / ppu;
    }

    public float width() {
        return w;
    }

    public float ppu() {
        return ppu;
    }

    private static BufferedImage toImage(Raster.Img img) {
        BufferedImage b = new BufferedImage(img.w(), img.h(), BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, img.w(), img.h(), img.argb(), 0, img.w());
        return b;
    }

    /** Multiplies a white image by a color (what the game's vertex color does). */
    private static BufferedImage tint(Raster.Img img, int argb) {
        int[] px = img.argb().clone();
        float a = ((argb >>> 24) & 255) / 255f;
        int r = (argb >> 16) & 255, gg = (argb >> 8) & 255, b = argb & 255;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int pa = Math.round(((p >>> 24) & 255) * a);
            int pr = ((p >> 16) & 255) * r / 255, pg = ((p >> 8) & 255) * gg / 255, pb = (p & 255) * b / 255;
            px[i] = (pa << 24) | (pr << 16) | (pg << 8) | pb;
        }
        BufferedImage out = new BufferedImage(img.w(), img.h(), BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, img.w(), img.h(), px, 0, img.w());
        return out;
    }

    private int px(float units) {
        return Math.round(units * ppu);
    }

    public void fillRound(float x, float y, float w, float h, float r, int top, int bottom, float stroke, int strokeColor) {
        int x0 = px(x), y0 = px(y), x1 = px(x + w), y1 = px(y + h);
        Raster.Img img = Raster.round(x1 - x0, y1 - y0, r * ppu, top, bottom, stroke * ppu, strokeColor);
        g.drawImage(toImage(img), x0, y0, null);
    }

    public void shadow(float x, float y, float w, float h, float r, float blur, float alpha) {
        int x0 = px(x), y0 = px(y), x1 = px(x + w), y1 = px(y + h);
        Raster.Img img = Raster.shadow(x1 - x0, y1 - y0, r * ppu, blur * ppu);
        g.drawImage(tint(img, ((int) (alpha * 255) << 24) | 0xFFFFFF), Math.round(x0 - img.ox()), Math.round(y0 - img.oy()), null);
    }

    private static Raster.Img glowImg;

    public void glow(float cx, float cy, float rx, float ry, int argb) {
        if (glowImg == null) glowImg = Raster.glow(128);
        Composite old = g.getComposite();
        g.setComposite(new AddComposite());
        g.drawImage(tint(glowImg, argb), px(cx - rx), px(cy - ry), px(cx + rx) - px(cx - rx), px(cy + ry) - px(cy - ry), null);
        g.setComposite(old);
    }

    public void disk(float cx, float cy, float r, int argb) {
        int size = Math.max(1, Math.round(r * 2 * ppu));
        g.drawImage(tint(Raster.disk(size), argb), Math.round(cx * ppu - size / 2f), Math.round(cy * ppu - size / 2f), null);
    }

    public void text(String s, Fonts.Style style, float x, float y, int argb) {
        if (s == null || s.isEmpty()) return;
        Raster.Img img = Raster.text(s, style, style.size() * ppu);
        float baseline = (y + Fonts.ascent(style)) * ppu;
        g.drawImage(tint(img, argb), Math.round(x * ppu - img.ox()), Math.round(baseline - img.oy()), null);
    }

    public void icon(String name, float x, float y, float size, int argb) {
        int s = Math.max(1, Math.round(size * ppu));
        g.drawImage(tint(Raster.icon(name, s), argb), px(x), px(y), null);
    }

    public void picture(String key, BufferedImage image, float x, float y, float w, float h, float r, float alpha) {
        int x0 = px(x), y0 = px(y);
        Raster.Img img = Raster.roundedPicture(image, px(x + w) - x0, px(y + h) - y0, r * ppu);
        g.drawImage(tint(img, ((int) (alpha * 255) << 24) | 0xFFFFFF), x0, y0, null);
    }

    public void space(int accent, float t) {
        int W = out.getWidth(), H = out.getHeight();
        BufferedImage base = SpaceArt.base(W / 2, H / 2, accent);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, W, H, null);
        // Beams in CSS pixels; the canvas is 270 units = H pixels; CSS reference height 1080.
        float css = H / 1080f;
        beam(accent, t, -0.12f * W, -0.30f * H, 0.34f * W, 1.50f * H, -38, 0.85f, 14, 0, css);
        beam(accent, t, -0.04f * W, -0.30f * H, 0.18f * W, 1.50f * H, -24, 0.45f, 19, 6, css);
        int sc = SpaceArt.starColor(accent);
        for (SpaceArt.Star s : SpaceArt.stars(W / css, H / css, t)) {
            g.drawImage(tint(Raster.disk(Math.max(1, Math.round(s.r() * 2 * css))), argbOf(s.alpha(), sc)),
                    Math.round((s.x() - s.r()) * css), Math.round((s.y() - s.r()) * css), null);
        }
        for (SpaceArt.Bokeh b : SpaceArt.bokeh(W / css, H / css, t)) {
            glow(b.x() * css / ppu, b.y() * css / ppu, b.r() * 4 * css / ppu, b.r() * 4 * css / ppu, argbOf(b.alpha(), accent));
        }
        g.drawImage(SpaceArt.vignette(W / 2, H / 2), 0, 0, W, H, null);
    }

    private void beam(int accent, float t, float left, float top, float bw, float bh, float rot, float opacity, float period, float delay, float css) {
        float phase = SpaceArt.swayPhase(t, period, delay);
        float blur = 12 * css;
        int scale = 2;
        int pad = Math.round(blur * 2 / scale);
        BufferedImage img = SpaceArt.beam(Math.round(bw / scale), Math.round(bh / scale), pad, blur / scale, accent);
        AffineTransform old = g.getTransform();
        g.translate(left + bw / 2 + 30 * css * phase, top);
        g.rotate(Math.toRadians(rot + 6 * phase));
        Composite oc = g.getComposite();
        g.setComposite(new AddComposite(opacity * (0.9f - 0.3f * phase)));
        g.drawImage(img, Math.round(-bw / 2 - pad * scale), -pad * scale, Math.round(bw + pad * 2 * scale), Math.round(bh + pad * 2 * scale), null);
        g.setComposite(oc);
        g.setTransform(old);
    }

    private static int argbOf(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24) | (rgb & 0xFFFFFF);
    }

    public void fillScreen(int argb) {
        g.setColor(new java.awt.Color(argb, true));
        g.fill(new Rectangle2D.Float(0, 0, out.getWidth(), out.getHeight()));
    }

    private Shape oldClip;

    public void clip(float x, float y, float w, float h) {
        oldClip = g.getClip();
        g.setClip(px(x), px(y), px(x + w) - px(x), px(y + h) - px(y));
    }

    public void unclip() {
        g.setClip(oldClip);
    }
}
