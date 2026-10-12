package app.blockyard.ui.core;

import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The launcher's space background, reproduced from its CSS (.space-scene, .space-beam,
 * .space-vignette and the star canvas) so the game menus look the same.
 */
public final class SpaceArt {
    private SpaceArt() {}

    static Color alpha(int rgb, float a) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.max(0, Math.min(255, Math.round(a * 255))));
    }

    static int mixRgb(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Gradients and vignette (everything static), at the given pixel size. */
    public static BufferedImage base(int w, int h, int accent) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Raster.quality(img);
        // linear-gradient(160deg, #0d0a1c 0%, #07060f 55%, #050409 100%)
        double th = Math.toRadians(160);
        double dx = Math.sin(th), dy = -Math.cos(th);
        double len = Math.abs(w * Math.sin(th)) + Math.abs(h * Math.cos(th));
        Point2D a = new Point2D.Double(w / 2.0 - dx * len / 2, h / 2.0 - dy * len / 2);
        Point2D b = new Point2D.Double(w / 2.0 + dx * len / 2, h / 2.0 + dy * len / 2);
        g.setPaint(new LinearGradientPaint(a, b, new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x0d0a1c), new Color(0x07060f), new Color(0x050409)}));
        g.fill(new Rectangle2D.Float(0, 0, w, h));
        // radial-gradient(ellipse 60% 50% at 90% 100%, accent 14%, transparent 70%)
        ellipse(g, w, h, 0.90, 1.00, 0.60, 0.50, alpha(accent, 0.14f), 0.70f);
        // radial-gradient(ellipse 70% 60% at 15% 0%, accent 30%, transparent 70%)
        ellipse(g, w, h, 0.15, 0.00, 0.70, 0.60, alpha(accent, 0.30f), 0.70f);
        g.dispose();
        return img;
    }

    /** The vignette on top of everything: radial-gradient(ellipse at 50% 40%, transparent 55%, rgba(0,0,0,.55)). */
    public static BufferedImage vignette(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Raster.quality(img);
        double cx = w * 0.5, cy = h * 0.4;
        double rx = w * 0.5 * Math.sqrt(2), ry = h * 0.6 * Math.sqrt(2);
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.scale(rx, ry);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 1f, new Point2D.Double(0, 0), new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(0, 0, 0, 140)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, t));
        g.fill(new Rectangle2D.Float(0, 0, w, h));
        g.dispose();
        return img;
    }

    private static void ellipse(Graphics2D g, int w, int h, double px, double py, double sx, double sy, Color c, float stop) {
        AffineTransform t = new AffineTransform();
        t.translate(w * px, h * py);
        t.scale(w * sx, h * sy);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 1f, new Point2D.Double(0, 0), new float[]{0f, stop},
                new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, t));
        g.fill(new Rectangle2D.Float(0, 0, w, h));
    }

    /**
     * One beam (the .space-beam element) of the given size in pixels, unrotated: a cone opening
     * downwards with the CSS gradient, blurred. Padding of {@code pad} pixels on every side.
     */
    public static BufferedImage beam(int bw, int bh, int pad, float blur, int accent) {
        BufferedImage img = new BufferedImage(bw + pad * 2, bh + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Raster.quality(img);
        // linear-gradient(180deg, accent+white 0%, accent .55 25%, accent .18 55%, transparent 85%)
        int light = mixRgb(accent, 0xFFFFFF, 0.40f);
        g.setPaint(new LinearGradientPaint(new Point2D.Float(0, pad), new Point2D.Float(0, pad + bh),
                new float[]{0f, 0.25f, 0.55f, 0.85f, 1f},
                new Color[]{new Color(light), alpha(accent, 0.55f), alpha(accent, 0.18f), alpha(accent, 0f), alpha(accent, 0f)}));
        // clip-path: polygon(46% 0, 54% 0, 100% 100%, 0 100%)
        GeneralPath cone = new GeneralPath();
        cone.moveTo(pad + bw * 0.46, pad);
        cone.lineTo(pad + bw * 0.54, pad);
        cone.lineTo(pad + bw, pad + bh);
        cone.lineTo(pad, pad + bh);
        cone.closePath();
        g.fill(cone);
        g.dispose();
        Raster.boxBlur(img, Math.max(1, Math.round(blur / 1.7f)), 3);
        return img;
    }

    // ------------------------------------------------------------------ stars (the launcher's canvas)

    public record Star(float x, float y, float r, float alpha) {}

    public record Bokeh(float x, float y, float r, float alpha) {}

    private static final int SEED = 7;

    /** Stars for a canvas of w x h CSS pixels at time t, like SpaceScene.tsx. */
    public static Star[] stars(float w, float h, float t) {
        int n = Math.round(w * h / 2600f);
        java.util.Random rnd = new java.util.Random(SEED);
        Star[] out = new Star[n];
        for (int i = 0; i < n; i++) {
            float x0 = rnd.nextFloat() * w, y0 = rnd.nextFloat() * h, depth = rnd.nextFloat();
            float p = rnd.nextFloat() * 6.283f, s = 0.6f + rnd.nextFloat() * 1.8f;
            float r = 0.3f + depth * 1.1f;
            float x = mod(x0 + (2 + depth * 7) * t, w + 4) - 2, y = mod(y0 + (1 + depth * 4) * t, h + 4) - 2;
            float tw = 0.45f + 0.55f * Math.abs((float) Math.sin(p + t * s));
            out[i] = new Star(x, y, r, tw * (0.35f + r * 0.45f));
        }
        return out;
    }

    public static Bokeh[] bokeh(float w, float h, float t) {
        int n = Math.round(w * h / 2600f / 28f) + 6;
        java.util.Random rnd = new java.util.Random(SEED + 1);
        Bokeh[] out = new Bokeh[n];
        for (int i = 0; i < n; i++) {
            float x0 = rnd.nextFloat() * w, y0 = rnd.nextFloat() * h;
            float r = 1.5f + rnd.nextFloat() * 3.5f, a = 0.35f + rnd.nextFloat() * 0.5f;
            float vx = -6 + rnd.nextFloat() * 12, vy = -10 - rnd.nextFloat() * 10, p = rnd.nextFloat() * 6.283f;
            float x = mod(x0 + vx * t + (float) Math.sin(t * 0.6 + p) * 6, w + 40) - 20;
            float y = mod(y0 + vy * t, h + 40) - 20;
            float pulse = 0.6f + 0.4f * (float) Math.sin(t * 1.3 + p);
            out[i] = new Bokeh(x, y, r, a * pulse);
        }
        return out;
    }

    private static float mod(float v, float m) {
        float r = v % m;
        return r < 0 ? r + m : r;
    }

    public static int starColor(int accent) {
        int ar = (accent >> 16) & 255, ag = (accent >> 8) & 255, ab = accent & 255;
        return (Math.min(255, Math.round(215 + ar * 0.16f)) << 16) | (Math.min(255, Math.round(215 + ag * 0.16f)) << 8)
                | Math.min(255, Math.round(225 + ab * 0.12f));
    }

    /** Beam sway, like the beam-sway keyframes (ease-in-out between the two poses). */
    public static float swayPhase(float t, float period, float delay) {
        return (float) (1 - Math.cos(2 * Math.PI * (t + delay) / period)) / 2;
    }
}
