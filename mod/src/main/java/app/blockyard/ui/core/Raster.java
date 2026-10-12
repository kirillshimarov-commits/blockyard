package app.blockyard.ui.core;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.font.TextLayout;

/** Java2D rasterization of UI pieces into white-or-colored ARGB images, at exact pixel sizes. */
public final class Raster {
    /** An image; (ox, oy) is where the logical origin (text baseline-left, shape top-left) sits in it. */
    public record Img(int w, int h, int[] argb, float ox, float oy) {}

    private Raster() {}

    public static Graphics2D quality(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    public static Img of(BufferedImage img, float ox, float oy) {
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        return new Img(img.getWidth(), img.getHeight(), px, ox, oy);
    }

    /** White text; origin = left end of the baseline. */
    public static Img text(String s, Fonts.Style style, float px) {
        TextLayout tl = Fonts.layout(s, style, px);
        int pad = 2;
        int w = (int) Math.ceil(Math.max(1, tl.getAdvance())) + pad * 2;
        float ascent = tl.getAscent(), descent = tl.getDescent();
        int h = (int) Math.ceil(ascent + descent) + pad * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        g.setColor(Color.WHITE);
        tl.draw(g, pad, pad + ascent);
        g.dispose();
        return of(img, pad, pad + ascent);
    }

    /** Rounded rectangle with a vertical gradient fill and an optional inner stroke. */
    public static Img round(int w, int h, float r, int top, int bottom, float strokePx, int stroke) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, r * 2, r * 2);
        g.setPaint(top == bottom ? new Color(top, true) : new GradientPaint(0, 0, new Color(top, true), 0, h, new Color(bottom, true)));
        g.fill(shape);
        if (strokePx > 0 && (stroke >>> 24) != 0) {
            g.setColor(new Color(stroke, true));
            g.setStroke(new BasicStroke(strokePx));
            float s = strokePx / 2;
            g.draw(new RoundRectangle2D.Float(s, s, w - strokePx, h - strokePx, Math.max(0, r * 2 - strokePx), Math.max(0, r * 2 - strokePx)));
        }
        g.dispose();
        return of(img, 0, 0);
    }

    /** Black blurred rounded rectangle; the shape's top-left sits at (blur*2, blur*2). */
    public static Img shadow(int w, int h, float r, float blur) {
        // Blur at a reduced resolution: shadows are soft, and it keeps big panels fast.
        float scale = blur > 6 ? 6 / blur : 1;
        int pad = (int) Math.ceil(blur * 2);
        int sw = Math.max(1, Math.round((w + pad * 2) * scale)), sh = Math.max(1, Math.round((h + pad * 2) * scale));
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(small);
        g.setColor(Color.BLACK);
        g.fill(new RoundRectangle2D.Float(pad * scale, pad * scale, w * scale, h * scale, r * 2 * scale, r * 2 * scale));
        g.dispose();
        boxBlurAlpha(small, Math.max(1, Math.round(blur * scale / 2)), 3);
        BufferedImage full = new BufferedImage(w + pad * 2, h + pad * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = quality(full);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(small, 0, 0, full.getWidth(), full.getHeight(), null);
        g2.dispose();
        return of(full, pad, pad);
    }

    /** Soft round glow, white; drawn tinted and additive. */
    public static Img glow(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        float c = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double d = Math.hypot(x + 0.5 - c, y + 0.5 - c) / c;
                // Matches CSS radial-gradient(rgba(.55) 0, rgba(.25) 25%, 0 100%).
                double a = d >= 1 ? 0 : d < 0.25 ? 1 - (d / 0.25) * 0.55 : 0.45 * (1 - (d - 0.25) / 0.75);
                img.setRGB(x, y, ((int) Math.round(Math.max(0, a) * 255) << 24) | 0xFFFFFF);
            }
        }
        return of(img, 0, 0);
    }

    /** An anti-aliased disk, white. */
    public static Img disk(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(0, 0, size, size));
        g.dispose();
        return of(img, 0, 0);
    }

    /** A picture scaled into a rounded square (tile icons, world icons). */
    public static Img roundedPicture(BufferedImage src, int w, int h, float r) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, r * 2, r * 2));
        g.setComposite(AlphaComposite.SrcIn);
        // Small pixel-art icons stay crisp when scaled up a lot.
        boolean pixelArt = src.getWidth() <= 128 && w >= src.getWidth() * 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pixelArt
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
        int dw = (int) Math.round(src.getWidth() * scale), dh = (int) Math.round(src.getHeight() * scale);
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        return of(img, 0, 0);
    }

    /** Vector icons, white, at an exact pixel size. */
    public static Img icon(String name, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = quality(img);
        g.setColor(Color.WHITE);
        float s = size / 24f;
        g.scale(s, s);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "gear" -> {
                GeneralPath p = new GeneralPath();
                for (int i = 0; i < 32; i++) {
                    double a = Math.PI * 2 * i / 32;
                    double rr = (i % 4 == 1 || i % 4 == 2) ? 10.5 : 8.2;
                    double x = 12 + rr * Math.cos(a), y = 12 + rr * Math.sin(a);
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                p.closePath();
                g.fill(p);
                g.setComposite(AlphaComposite.Clear);
                g.fill(new Ellipse2D.Float(8.6f, 8.6f, 6.8f, 6.8f));
            }
            case "users" -> {
                g.draw(new Ellipse2D.Float(5, 3, 8, 8));
                GeneralPath p = new GeneralPath();
                p.moveTo(2, 21);
                p.curveTo(2, 15, 5, 15, 9, 15);
                p.curveTo(13, 15, 16, 15, 16, 21);
                g.draw(p);
                g.draw(new Line2D.Float(16, 3.2f, 16, 3.2f));
                GeneralPath q = new GeneralPath();
                q.moveTo(16, 3.13);
                q.curveTo(18.5, 3.8, 18.5, 10, 16, 10.9);
                g.draw(q);
                GeneralPath r = new GeneralPath();
                r.moveTo(19, 15.1);
                r.curveTo(21, 15.6, 22, 17.5, 22, 21);
                g.draw(r);
            }
            case "user" -> {
                g.draw(new Ellipse2D.Float(8, 3, 8, 8));
                GeneralPath p = new GeneralPath();
                p.moveTo(4, 21);
                p.curveTo(4, 15, 8, 14, 12, 14);
                p.curveTo(16, 14, 20, 15, 20, 21);
                g.draw(p);
            }
            case "play" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(7, 4);
                p.lineTo(20, 12);
                p.lineTo(7, 20);
                p.closePath();
                g.fill(p);
            }
            case "plus" -> {
                g.draw(new Line2D.Float(12, 5, 12, 19));
                g.draw(new Line2D.Float(5, 12, 19, 12));
            }
            case "check" -> {
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                GeneralPath p = new GeneralPath();
                p.moveTo(5, 12.5);
                p.lineTo(10, 17.5);
                p.lineTo(19.5, 7);
                g.draw(p);
            }
            case "close" -> {
                g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Float(7, 7, 17, 17));
                g.draw(new Line2D.Float(17, 7, 7, 17));
            }
            case "cube" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(12, 2);
                p.lineTo(21, 7);
                p.lineTo(21, 17);
                p.lineTo(12, 22);
                p.lineTo(3, 17);
                p.lineTo(3, 7);
                p.closePath();
                g.draw(p);
                GeneralPath q = new GeneralPath();
                q.moveTo(3, 7);
                q.lineTo(12, 12);
                q.lineTo(21, 7);
                q.moveTo(12, 12);
                q.lineTo(12, 22);
                g.draw(q);
            }
            case "globe" -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                g.draw(new Ellipse2D.Float(8, 3, 8, 18));
                g.draw(new Line2D.Float(3, 12, 21, 12));
            }
            case "chevron" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(9, 6);
                p.lineTo(15, 12);
                p.lineTo(9, 18);
                g.draw(p);
            }
            case "folder" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(3, 7);
                p.lineTo(3, 18);
                p.lineTo(21, 18);
                p.lineTo(21, 9);
                p.lineTo(11, 9);
                p.lineTo(9, 6);
                p.lineTo(3, 6);
                p.closePath();
                g.draw(p);
            }
            case "sound" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(4, 9);
                p.lineTo(8, 9);
                p.lineTo(13, 5);
                p.lineTo(13, 19);
                p.lineTo(8, 15);
                p.lineTo(4, 15);
                p.closePath();
                g.draw(p);
                GeneralPath q = new GeneralPath();
                q.moveTo(16.5, 8.5);
                q.curveTo(18.5, 10.5, 18.5, 13.5, 16.5, 15.5);
                g.draw(q);
            }
            case "keyboard" -> {
                g.draw(new RoundRectangle2D.Float(2, 6, 20, 12, 3, 3));
                for (int i = 0; i < 4; i++) g.fill(new Rectangle2D.Float(5.2f + i * 4, 9, 1.6f, 1.6f));
                g.draw(new Line2D.Float(7, 14.5f, 17, 14.5f));
            }
            case "chat" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(4, 5);
                p.lineTo(20, 5);
                p.lineTo(20, 16);
                p.lineTo(10, 16);
                p.lineTo(6, 20);
                p.lineTo(6, 16);
                p.lineTo(4, 16);
                p.closePath();
                g.draw(p);
            }
            case "shirt" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(8, 3);
                p.lineTo(3, 6);
                p.lineTo(5, 10);
                p.lineTo(7, 9);
                p.lineTo(7, 21);
                p.lineTo(17, 21);
                p.lineTo(17, 9);
                p.lineTo(19, 10);
                p.lineTo(21, 6);
                p.lineTo(16, 3);
                p.curveTo(15, 5, 9, 5, 8, 3);
                p.closePath();
                g.draw(p);
            }
            case "language" -> {
                g.draw(new Ellipse2D.Float(3, 3, 18, 18));
                GeneralPath p = new GeneralPath();
                p.moveTo(8, 16);
                p.lineTo(12, 7);
                p.lineTo(16, 16);
                p.moveTo(9.5, 13);
                p.lineTo(14.5, 13);
                g.draw(p);
            }
            case "eye" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(2, 12);
                p.curveTo(5, 6, 19, 6, 22, 12);
                p.curveTo(19, 18, 5, 18, 2, 12);
                p.closePath();
                g.draw(p);
                g.draw(new Ellipse2D.Float(9, 9, 6, 6));
            }
            case "layers" -> {
                GeneralPath p = new GeneralPath();
                p.moveTo(12, 3);
                p.lineTo(21, 8);
                p.lineTo(12, 13);
                p.lineTo(3, 8);
                p.closePath();
                p.moveTo(3, 12.5);
                p.lineTo(12, 17.5);
                p.lineTo(21, 12.5);
                p.moveTo(3, 16.5);
                p.lineTo(12, 21.5);
                p.lineTo(21, 16.5);
                g.draw(p);
            }
            case "monitor" -> {
                g.draw(new RoundRectangle2D.Float(3, 4, 18, 12, 2, 2));
                g.draw(new Line2D.Float(8, 20, 16, 20));
                g.draw(new Line2D.Float(12, 16, 12, 20));
            }
            default -> g.fill(new Ellipse2D.Float(6, 6, 12, 12));
        }
        g.dispose();
        return of(img, 0, 0);
    }

    /** Three-pass box blur of the alpha channel (keeps RGB), close to a gaussian. */
    public static void boxBlurAlpha(BufferedImage img, int radius, int passes) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] a = new int[w * h], tmp = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = px[i] >>> 24;
        for (int p = 0; p < passes; p++) {
            blurLine(a, tmp, w, h, radius, true);
            blurLine(tmp, a, w, h, radius, false);
        }
        for (int i = 0; i < a.length; i++) px[i] = (Math.min(255, a[i]) << 24) | (px[i] & 0xFFFFFF);
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    /** Box blur of a full RGBA image (premultiplied internally), for soft light shapes. */
    public static void boxBlur(BufferedImage img, int radius, int passes) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[][] ch = new int[4][w * h];
        for (int i = 0; i < px.length; i++) {
            int al = px[i] >>> 24;
            ch[0][i] = al;
            ch[1][i] = ((px[i] >> 16) & 255) * al / 255;
            ch[2][i] = ((px[i] >> 8) & 255) * al / 255;
            ch[3][i] = (px[i] & 255) * al / 255;
        }
        int[] tmp = new int[w * h];
        for (int[] c : ch) {
            for (int p = 0; p < passes; p++) {
                blurLine(c, tmp, w, h, radius, true);
                blurLine(tmp, c, w, h, radius, false);
            }
        }
        for (int i = 0; i < px.length; i++) {
            int al = Math.min(255, ch[0][i]);
            int r = al == 0 ? 0 : Math.min(255, ch[1][i] * 255 / al);
            int gg = al == 0 ? 0 : Math.min(255, ch[2][i] * 255 / al);
            int b = al == 0 ? 0 : Math.min(255, ch[3][i] * 255 / al);
            px[i] = (al << 24) | (r << 16) | (gg << 8) | b;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static void blurLine(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += src[index(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
            for (int i = 0; i < len; i++) {
                dst[index(line, i, w, horizontal)] = sum / div;
                int add = Math.min(len - 1, i + r + 1), sub = Math.max(0, i - r);
                sum += src[index(line, add, w, horizontal)] - src[index(line, sub, w, horizontal)];
            }
        }
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
