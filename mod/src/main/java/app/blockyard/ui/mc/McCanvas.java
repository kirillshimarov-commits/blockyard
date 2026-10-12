package app.blockyard.ui.mc;

import app.blockyard.ui.core.Canvas;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Raster;
import app.blockyard.ui.core.SpaceArt;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's implementation of the views' canvas: every piece is rasterized by Java2D at its exact
 * size in screen pixels, uploaded once, and drawn on whole pixels, so it looks exactly like the
 * offline previews (and the launcher) at any window size and GUI scale.
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
        ctx.draw();
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

    private Matrix4f matrix;

    private BufferBuilder begin(TextureCache.Entry e, boolean additive) {
        matrix = ctx.getMatrices().peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, e.id());
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
        } else {
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        }
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
    }

    private void end(BufferBuilder b) {
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    /** Premultiplied vertex color, with the canvas alpha applied. */
    private int pm(int argb) {
        float a = ((argb >>> 24) & 255) / 255f * alpha;
        int ai = Math.max(0, Math.min(255, Math.round(a * 255)));
        int r = Math.round(((argb >> 16) & 255) * a), g = Math.round(((argb >> 8) & 255) * a), b = Math.round((argb & 255) * a);
        return (ai << 24) | (r << 16) | (g << 8) | b;
    }

    private void vtx(BufferBuilder b, float x, float y, float u, float v, int color) {
        b.vertex(matrix, x / gs, y / gs, 0).texture(u, v).color(color);
    }

    /** One axis-aligned quad in pixels; colors already premultiplied. */
    private void quad(BufferBuilder b, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int top, int bottom) {
        vtx(b, x0, y0, u0, v0, top);
        vtx(b, x0, y1, u0, v1, bottom);
        vtx(b, x1, y1, u1, v1, bottom);
        vtx(b, x1, y0, u1, v0, top);
    }

    private void blit(TextureCache.Entry e, float x, float y, float w, float h, int argb, boolean additive) {
        if (((argb >>> 24) & 255) == 0 || alpha <= 0) return;
        int c = pm(argb);
        BufferBuilder b = begin(e, additive);
        quad(b, x, y, x + w, y + h, 0, 0, 1, 1, c, c);
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

    /** A white rounded shape (filled, or a ring of width sp) tinted top to bottom; nine-sliced when big. */
    private void shape(int x0, int y0, int W, int H, float rp, float sp, int top, int bottom) {
        int corner = (int) Math.ceil(Math.max(rp, sp)) + 1;
        int T = corner * 2 + 1;
        if (W < T || H < T) {
            TextureCache.Entry e = TextureCache.get("f|" + W + "|" + H + "|" + q(rp) + "|" + q(sp), false,
                    () -> sp > 0 ? Raster.round(W, H, rp, 0, 0, sp, WHITE) : Raster.round(W, H, rp, WHITE, WHITE, 0, 0));
            int ct = pm(top), cb = pm(bottom);
            BufferBuilder b = begin(e, false);
            quad(b, x0, y0, x0 + W, y0 + H, 0, 0, 1, 1, ct, cb);
            end(b);
            return;
        }
        TextureCache.Entry e = TextureCache.get("9|" + q(rp) + "|" + q(sp), false,
                () -> sp > 0 ? Raster.round(T, T, rp, 0, 0, sp, WHITE) : Raster.round(T, T, rp, WHITE, WHITE, 0, 0));
        float[] xs = {x0, x0 + corner, x0 + W - corner, x0 + W};
        float[] ys = {y0, y0 + corner, y0 + H - corner, y0 + H};
        float[] us = {0, corner / (float) T, (corner + 1) / (float) T, 1};
        int[] cs = new int[4];
        for (int i = 0; i < 4; i++) cs[i] = pm(lerpArgb(top, bottom, (ys[i] - y0) / H));
        BufferBuilder b = begin(e, false);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                quad(b, xs[col], ys[row], xs[col + 1], ys[row + 1], us[col], us[row], us[col + 1], us[row + 1], cs[row], cs[row + 1]);
            }
        }
        end(b);
    }

    private static int lerpArgb(int a, int b, float t) {
        if (a == b) return a;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 255, cb = (b >>> shift) & 255;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
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
        float cos = (float) Math.cos(th), sin = (float) Math.sin(th);
        float lx0 = -bw / 2 - pad * scale, ly0 = -pad * scale;
        float lx1 = lx0 + bw + pad * 2 * scale, ly1 = ly0 + bh + pad * 2 * scale;
        float a = opacity * (0.9f - 0.3f * phase);
        int c = pm((Math.round(a * 255) << 24) | 0xFFFFFF);
        BufferBuilder b = begin(e, true);
        float[][] pts = {{lx0, ly0, 0, 0}, {lx0, ly1, 0, 1}, {lx1, ly1, 1, 1}, {lx1, ly0, 1, 0}};
        for (float[] p : pts) vtx(b, ox + p[0] * cos - p[1] * sin, oy + p[0] * sin + p[1] * cos, p[2], p[3], c);
        end(b);
    }

    private void stars(int accent, float t, float cssW, float cssH, float css) {
        int sc = SpaceArt.starColor(accent);
        Map<Integer, java.util.List<float[]>> bySize = new HashMap<>();
        for (SpaceArt.Star s : SpaceArt.stars(cssW, cssH, t)) {
            int size = Math.max(1, Math.round(s.r() * 2 * css));
            bySize.computeIfAbsent(size, k -> new java.util.ArrayList<>())
                    .add(new float[]{Math.round((s.x() - s.r()) * css), Math.round((s.y() - s.r()) * css), s.alpha()});
        }
        for (Map.Entry<Integer, java.util.List<float[]>> en : bySize.entrySet()) {
            int size = en.getKey();
            BufferBuilder b = begin(disk(size), false);
            for (float[] p : en.getValue()) {
                int c = pm((Math.max(0, Math.min(255, Math.round(p[2] * 255))) << 24) | sc);
                quad(b, p[0], p[1], p[0] + size, p[1] + size, 0, 0, 1, 1, c, c);
            }
            end(b);
        }
        SpaceArt.Bokeh[] bokeh = SpaceArt.bokeh(cssW, cssH, t);
        if (bokeh.length == 0) return;
        BufferBuilder b = begin(glowTex(), true);
        for (SpaceArt.Bokeh k : bokeh) {
            float r = k.r() * 4 * css;
            float x0 = Math.round(k.x() * css - r), y0 = Math.round(k.y() * css - r), x1 = Math.round(k.x() * css + r), y1 = Math.round(k.y() * css + r);
            int c = pm((Math.max(0, Math.min(255, Math.round(k.alpha() * 255))) << 24) | (accent & 0xFFFFFF));
            quad(b, x0, y0, x1, y1, 0, 0, 1, 1, c, c);
        }
        end(b);
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
