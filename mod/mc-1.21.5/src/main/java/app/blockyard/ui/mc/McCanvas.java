package app.blockyard.ui.mc;

import app.blockyard.ui.core.Canvas;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Raster;
import app.blockyard.ui.core.SpaceArt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's implementation of the views' canvas: every piece is rasterized by Java2D at its exact
 * size in screen pixels, uploaded once, and drawn on whole pixels, so it looks exactly like the
 * offline previews (and the launcher) at any window size and GUI scale.
 *
 * From 1.21.5 the game draws only through render pipelines, so every piece goes through
 * DrawContext.drawTexture, placed by the matrix stack. The GUI shader keeps any non-zero alpha
 * there, so straight (not premultiplied) textures tinted by the vertex color look right.
 */
public final class McCanvas implements Canvas {
    private final DrawContext ctx;
    private final float gs;
    private final int fbW, fbH;
    private final float ppu;
    private final float width;
    /** Multiplies the alpha of everything drawn (fading widgets). */
    public float alpha = 1;

    /** A canvas 270 units tall over the whole window. */
    public McCanvas(DrawContext ctx) {
        this(ctx, -1);
    }

    /** With unitPx > 0, one unit is that many pixels (used with GUI units: unitPx = GUI scale). */
    public McCanvas(DrawContext ctx, float unitPx) {
        this.ctx = ctx;
        Window win = MinecraftClient.getInstance().getWindow();
        gs = (float) win.getScaleFactor();
        fbW = win.getFramebufferWidth();
        fbH = win.getFramebufferHeight();
        ppu = unitPx > 0 ? unitPx : fbH / 270f;
        width = fbW / ppu;
        TextureCache.tick();
    }

    public float width() {
        return width;
    }

    public float ppu() {
        return ppu;
    }

    public float guiScale() {
        return gs;
    }

    private int px(float units) {
        return Math.round(units * ppu);
    }

    private static int q(float v) {
        return Math.round(v * 100);
    }

    // ------------------------------------------------------------------ low level

    /** Quads of one texture and one tint color. */
    private record Batch(TextureCache.Entry e, int color) {}

    /** Additive pieces (glows, beams) are drawn with the normal blending of the GUI pipeline. */
    private Batch begin(TextureCache.Entry e, boolean additive, int color) {
        return new Batch(e, color);
    }

    private void end(Batch b) {
        // Each quad is drawn as it is added.
    }

    /** Vertex color with the canvas alpha applied (textures carry straight alpha). */
    private int pm(int argb) {
        float a = ((argb >>> 24) & 255) / 255f * alpha;
        int ai = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (ai << 24) | (argb & 0xFFFFFF);
    }

    /** One axis-aligned quad in pixels, showing the part u0..u1, v0..v1 of the texture. */
    private void quad(Batch b, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
        if (((b.color() >>> 24) & 255) == 0 || x1 <= x0 || y1 <= y0) return;
        int tw = b.e().w(), th = b.e().h();
        int u = Math.round(u0 * tw), v = Math.round(v0 * th);
        int rw = Math.max(1, Math.round((u1 - u0) * tw)), rh = Math.max(1, Math.round((v1 - v0) * th));
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x0 / gs, y0 / gs, 0);
        m.scale((x1 - x0) / gs / rw, (y1 - y0) / gs / rh, 1);
        ctx.drawTexture(RenderLayer::getGuiTextured, b.e().id(), 0, 0, u, v, rw, rh, rw, rh, tw, th, b.color());
        m.pop();
    }

    private void blit(TextureCache.Entry e, float x, float y, float w, float h, int argb, boolean additive) {
        if (((argb >>> 24) & 255) == 0 || alpha <= 0) return;
        Batch b = begin(e, additive, pm(argb));
        quad(b, x, y, x + w, y + h, 0, 0, 1, 1);
        end(b);
    }

    // ------------------------------------------------------------------ shapes

    private static final int WHITE = 0xFFFFFFFF;

    public void fillRound(float x, float y, float w, float h, float r, int top, int bottom, float stroke, int strokeColor) {
        int x0 = px(x), y0 = px(y), x1 = px(x + w), y1 = px(y + h);
        int W = x1 - x0, H = y1 - y0;
        if (W <= 0 || H <= 0) return;
        float rp = Math.max(0, Math.min(r * ppu, Math.min(W, H) / 2f));
        if ((top >>> 24) != 0 || (bottom >>> 24) != 0) shape(x0, y0, W, H, rp, 0, top, bottom);
        float sp = stroke * ppu;
        if (sp > 0 && (strokeColor >>> 24) != 0) shape(x0, y0, W, H, rp, Math.max(sp, 0.75f), strokeColor, strokeColor);
    }

    /** A rounded shape (filled, or a ring of width sp); nine-sliced and tinted when it is one color. */
    private void shape(int x0, int y0, int W, int H, float rp, float sp, int top, int bottom) {
        int corner = (int) Math.ceil(Math.max(rp, sp)) + 1;
        int T = corner * 2 + 1;
        if (top != bottom) {
            // Gradients are baked into the texture at their exact size.
            TextureCache.Entry e = TextureCache.get("g|" + W + "|" + H + "|" + q(rp) + "|" + top + "|" + bottom, false,
                    () -> Raster.round(W, H, rp, top, bottom, 0, 0));
            Batch b = begin(e, false, pm(WHITE));
            quad(b, x0, y0, x0 + W, y0 + H, 0, 0, 1, 1);
            end(b);
            return;
        }
        if (W < T || H < T) {
            TextureCache.Entry e = TextureCache.get("f|" + W + "|" + H + "|" + q(rp) + "|" + q(sp), false,
                    () -> sp > 0 ? Raster.round(W, H, rp, 0, 0, sp, WHITE) : Raster.round(W, H, rp, WHITE, WHITE, 0, 0));
            Batch b = begin(e, false, pm(top));
            quad(b, x0, y0, x0 + W, y0 + H, 0, 0, 1, 1);
            end(b);
            return;
        }
        TextureCache.Entry e = TextureCache.get("9|" + q(rp) + "|" + q(sp), false,
                () -> sp > 0 ? Raster.round(T, T, rp, 0, 0, sp, WHITE) : Raster.round(T, T, rp, WHITE, WHITE, 0, 0));
        float[] xs = {x0, x0 + corner, x0 + W - corner, x0 + W};
        float[] ys = {y0, y0 + corner, y0 + H - corner, y0 + H};
        float[] us = {0, corner / (float) T, (corner + 1) / (float) T, 1};
        Batch b = begin(e, false, pm(top));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                quad(b, xs[col], ys[row], xs[col + 1], ys[row + 1], us[col], us[row], us[col + 1], us[row + 1]);
            }
        }
        end(b);
    }

    public void shadow(float x, float y, float w, float h, float r, float blur, float alpha) {
        int x0 = px(x), y0 = px(y), W = px(x + w) - x0, H = px(y + h) - y0;
        if (W <= 0 || H <= 0 || alpha <= 0) return;
        float rp = Math.min(r * ppu, Math.min(W, H) / 2f), bp = blur * ppu;
        TextureCache.Entry e = TextureCache.get("sh|" + W + "|" + H + "|" + q(rp) + "|" + q(bp), true, () -> Raster.shadow(W, H, rp, bp));
        blit(e, Math.round(x0 - e.ox()), Math.round(y0 - e.oy()), e.w(), e.h(), (Math.round(Math.min(1, alpha) * 255) << 24), false);
    }

    private TextureCache.Entry glowTex() {
        return TextureCache.get("glow", true, () -> Raster.glow(128));
    }

    public void glow(float cx, float cy, float rx, float ry, int argb) {
        int x0 = px(cx - rx), y0 = px(cy - ry), x1 = px(cx + rx), y1 = px(cy + ry);
        if (x1 <= x0 || y1 <= y0) return;
        blit(glowTex(), x0, y0, x1 - x0, y1 - y0, argb, true);
    }

    private TextureCache.Entry disk(int size) {
        return TextureCache.get("d|" + size, false, () -> Raster.disk(size));
    }

    public void disk(float cx, float cy, float r, int argb) {
        int size = Math.max(1, Math.round(r * 2 * ppu));
        blit(disk(size), Math.round(cx * ppu - size / 2f), Math.round(cy * ppu - size / 2f), size, size, argb, false);
    }

    public void text(String s, Fonts.Style style, float x, float y, int argb) {
        if (s == null || s.isEmpty()) return;
        float size = style.size() * ppu;
        TextureCache.Entry e = TextureCache.get("t|" + style.face().ordinal() + "|" + q(size) + "|" + style.tracking() + "|" + s, false,
                () -> Raster.text(s, style, size));
        float baseline = (y + Fonts.ascent(style)) * ppu;
        blit(e, Math.round(x * ppu - e.ox()), Math.round(baseline - e.oy()), e.w(), e.h(), argb, false);
    }

    /**
     * Text by its baseline, for a matrix that scales by {@code scale}: rasterized at the final size
     * on screen. With scale 1 it lands on whole pixels like {@link #text}.
     */
    public void textAt(String s, Fonts.Style style, float x, float baseline, int argb, float scale) {
        if (s == null || s.isEmpty()) return;
        float size = style.size() * ppu * scale;
        TextureCache.Entry e = TextureCache.get("t|" + style.face().ordinal() + "|" + q(size) + "|" + style.tracking() + "|" + s, false,
                () -> Raster.text(s, style, size));
        if (Math.abs(scale - 1) < 0.001f) {
            blit(e, Math.round(x * ppu - e.ox()), Math.round(baseline * ppu - e.oy()), e.w(), e.h(), argb, false);
        } else {
            blit(e, x * ppu - e.ox() / scale, baseline * ppu - e.oy() / scale, e.w() / scale, e.h() / scale, argb, false);
        }
    }

    public void icon(String name, float x, float y, float size, int argb) {
        int s = Math.max(1, Math.round(size * ppu));
        TextureCache.Entry e = TextureCache.get("i|" + name + "|" + s, false, () -> Raster.icon(name, s));
        blit(e, px(x), px(y), s, s, argb, false);
    }

    public void picture(String key, BufferedImage image, float x, float y, float w, float h, float r, float alpha) {
        if (image == null) return;
        int x0 = px(x), y0 = px(y), W = px(x + w) - x0, H = px(y + h) - y0;
        if (W <= 0 || H <= 0) return;
        float rp = r * ppu;
        TextureCache.Entry e = TextureCache.get("p|" + key + "|" + System.identityHashCode(image) + "|" + W + "|" + H + "|" + q(rp), false,
                () -> Raster.roundedPicture(image, W, H, rp));
        blit(e, x0, y0, W, H, (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | 0xFFFFFF, false);
    }

    // ------------------------------------------------------------------ space

    public void space(int accent, float t) {
        int W = fbW, H = fbH;
        int bw2 = Math.max(1, W / 2), bh2 = Math.max(1, H / 2);
        TextureCache.Entry base = TextureCache.get("sb|" + bw2 + "|" + bh2 + "|" + accent, true, () -> Raster.of(SpaceArt.base(bw2, bh2, accent), 0, 0));
        float keep = alpha;
        alpha = 1;
        blit(base, 0, 0, W, H, WHITE, false);
        float css = H / 1080f;
        beam(accent, t, -0.12f * W, -0.30f * H, 0.34f * W, 1.50f * H, -38, 0.85f, 14, 0, css);
        beam(accent, t, -0.04f * W, -0.30f * H, 0.18f * W, 1.50f * H, -24, 0.45f, 19, 6, css);
        stars(accent, t, W / css, H / css, css);
        TextureCache.Entry vig = TextureCache.get("sv|" + bw2 + "|" + bh2, true, () -> Raster.of(SpaceArt.vignette(bw2, bh2), 0, 0));
        blit(vig, 0, 0, W, H, WHITE, false);
        alpha = keep;
    }

    private void beam(int accent, float t, float left, float top, float bw, float bh, float rot, float opacity, float period, float delay, float css) {
        float phase = SpaceArt.swayPhase(t, period, delay);
        float blur = 12 * css;
        int scale = 2;
        int pad = Math.round(blur * 2 / scale);
        int iw = Math.round(bw / scale), ih = Math.round(bh / scale);
        TextureCache.Entry e = TextureCache.get("bm|" + iw + "|" + ih + "|" + pad + "|" + accent, true,
                () -> Raster.of(SpaceArt.beam(iw, ih, pad, blur / scale, accent), 0, 0));
        float ox = left + bw / 2 + 30 * css * phase, oy = top;
        double th = Math.toRadians(rot + 6 * phase);
        float lx0 = -bw / 2 - pad * scale, ly0 = -pad * scale;
        float lx1 = lx0 + bw + pad * 2 * scale, ly1 = ly0 + bh + pad * 2 * scale;
        float a = opacity * (0.9f - 0.3f * phase);
        Batch b = begin(e, true, pm((Math.round(a * 255) << 24) | 0xFFFFFF));
        // Turned around its top center by the matrix, then drawn as a plain quad.
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(ox / gs, oy / gs, 0);
        m.multiply(new Quaternionf().rotateZ((float) th));
        quad(b, lx0, ly0, lx1, ly1, 0, 0, 1, 1);
        m.pop();
        end(b);
    }

    private void stars(int accent, float t, float cssW, float cssH, float css) {
        int sc = SpaceArt.starColor(accent);
        // Batched by size and by alpha in 24 steps: one draw per group.
        Map<Integer, java.util.List<float[]>> groups = new HashMap<>();
        for (SpaceArt.Star s : SpaceArt.stars(cssW, cssH, t)) {
            int size = Math.max(1, Math.round(s.r() * 2 * css));
            int level = Math.max(1, Math.min(24, Math.round(s.alpha() * 24)));
            groups.computeIfAbsent(size * 100 + level, k -> new java.util.ArrayList<>())
                    .add(new float[]{Math.round((s.x() - s.r()) * css), Math.round((s.y() - s.r()) * css)});
        }
        for (Map.Entry<Integer, java.util.List<float[]>> en : groups.entrySet()) {
            int size = en.getKey() / 100, level = en.getKey() % 100;
            Batch b = begin(disk(size), false, pm((Math.round(level / 24f * 255) << 24) | sc));
            for (float[] p : en.getValue()) quad(b, p[0], p[1], p[0] + size, p[1] + size, 0, 0, 1, 1);
            end(b);
        }
        for (SpaceArt.Bokeh k : SpaceArt.bokeh(cssW, cssH, t)) {
            float r = k.r() * 4 * css;
            float x0 = Math.round(k.x() * css - r), y0 = Math.round(k.y() * css - r), x1 = Math.round(k.x() * css + r), y1 = Math.round(k.y() * css + r);
            blit(glowTex(), x0, y0, x1 - x0, y1 - y0, (Math.max(0, Math.min(255, Math.round(k.alpha() * 255))) << 24) | (accent & 0xFFFFFF), true);
        }
    }

    public void fillScreen(int argb) {
        TextureCache.Entry e = TextureCache.get("px", false, () -> new Raster.Img(1, 1, new int[]{WHITE}, 0, 0));
        blit(e, 0, 0, fbW, fbH, argb, false);
    }

    /** A solid rectangle in units (no rounding). */
    public void rect(float x, float y, float w, float h, int argb) {
        TextureCache.Entry e = TextureCache.get("px", false, () -> new Raster.Img(1, 1, new int[]{WHITE}, 0, 0));
        int x0 = px(x), y0 = px(y);
        blit(e, x0, y0, px(x + w) - x0, px(y + h) - y0, argb, false);
    }

    private int clipDepth = 0;

    public void clip(float x, float y, float w, float h) {
        int x0 = (int) Math.floor(px(x) / gs), y0 = (int) Math.floor(px(y) / gs);
        int x1 = (int) Math.ceil(px(x + w) / gs), y1 = (int) Math.ceil(px(y + h) / gs);
        ctx.enableScissor(x0, y0, x1, y1);
        clipDepth++;
    }

    public void unclip() {
        if (clipDepth <= 0) return;
        clipDepth--;
        ctx.disableScissor();
    }
}
