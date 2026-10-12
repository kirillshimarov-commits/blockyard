package app.blockyard.ui.screen;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Drawing toolkit: smooth (bilinear) textures drawn as float quads with per-vertex color, so shapes
 * have anti-aliased edges at any GUI scale, and TTF fonts for text.
 */
final class Ui {
    static final Identifier BODY = Identifier.of("blockyard-ui", "body");
    static final Identifier BOLD = Identifier.of("blockyard-ui", "bold");
    static final Identifier SMALL = Identifier.of("blockyard-ui", "small");
    static final Identifier HEAD = Identifier.of("blockyard-ui", "head");
    static final Identifier TITLE = Identifier.of("blockyard-ui", "title");

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    private Ui() {}

    /** A texture from assets/blockyard-ui/textures/ui, loaded once with smooth filtering. */
    static Identifier tex(String name) {
        Identifier cached = TEXTURES.get(name);
        if (cached != null) return cached;
        Identifier id = Identifier.of("blockyard-ui", "ui/" + name);
        try (InputStream in = Ui.class.getResourceAsStream("/assets/blockyard-ui/textures/ui/" + name + ".png")) {
            NativeImageBackedTexture texture = new NativeImageBackedTexture(NativeImage.read(in));
            texture.setFilter(true, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        } catch (Exception e) {
            // A missing texture just draws nothing useful; never crash the menu over it.
        }
        TEXTURES.put(name, id);
        return id;
    }

    // ------------------------------------------------------------------ colors

    static int argb(float alpha, int rgb) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ quads

    private static BufferBuilder begin(DrawContext ctx, Identifier texture) {
        ctx.draw();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, texture);
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
    }

    private static void quad(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1,
                             float u0, float v0, float u1, float v1, int top, int bottom) {
        b.vertex(m, x0, y0, 0).texture(u0, v0).color(top);
        b.vertex(m, x0, y1, 0).texture(u0, v1).color(bottom);
        b.vertex(m, x1, y1, 0).texture(u1, v1).color(bottom);
        b.vertex(m, x1, y0, 0).texture(u1, v0).color(top);
    }

    private static void end(BufferBuilder b) {
        BufferRenderer.drawWithGlobalProgram(b.end());
    }

    /** Draws a whole texture into the given rectangle, tinted. */
    static void image(DrawContext ctx, Identifier texture, float x, float y, float w, float h, int argb) {
        BufferBuilder b = begin(ctx, texture);
        quad(b, ctx.getMatrices().peek().getPositionMatrix(), x, y, x + w, y + h, 0, 0, 1, 1, argb, argb);
        end(b);
    }

    /** Same, with additive blending: for light (glows, beams, stars). */
    static void light(DrawContext ctx, Identifier texture, float x, float y, float w, float h, int argb) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        image(ctx, texture, x, y, w, h, argb);
        RenderSystem.defaultBlendFunc();
    }

    /** Anti-aliased rounded rectangle, optionally with a vertical gradient (top to bottom color). */
    static void round(DrawContext ctx, float x, float y, float w, float h, float r, int top, int bottom) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        BufferBuilder b = begin(ctx, tex("circle"));
        float x1 = x + w, y1 = y + h;
        int midTop = mixArgb(top, bottom, r / h), midBottom = mixArgb(top, bottom, 1 - r / h);
        // Corners from the four quarters of the disk texture.
        quad(b, m, x, y, x + r, y + r, 0, 0, 0.5f, 0.5f, top, midTop);
        quad(b, m, x1 - r, y, x1, y + r, 0.5f, 0, 1, 0.5f, top, midTop);
        quad(b, m, x, y1 - r, x + r, y1, 0, 0.5f, 0.5f, 1, midBottom, bottom);
        quad(b, m, x1 - r, y1 - r, x1, y1, 0.5f, 0.5f, 1, 1, midBottom, bottom);
        // Edges and middle from the disk's solid center.
        float c = 0.5f;
        quad(b, m, x + r, y, x1 - r, y + r, c, c, c, c, top, midTop);
        quad(b, m, x + r, y1 - r, x1 - r, y1, c, c, c, c, midBottom, bottom);
        quad(b, m, x, y + r, x1, y1 - r, c, c, c, c, midTop, midBottom);
        end(b);
    }

    static void round(DrawContext ctx, float x, float y, float w, float h, float r, int argb) {
        round(ctx, x, y, w, h, r, argb, argb);
    }

    static void circle(DrawContext ctx, float cx, float cy, float radius, int argb) {
        image(ctx, tex("circle"), cx - radius, cy - radius, radius * 2, radius * 2, argb);
    }

    /** Soft shadow under a panel. */
    static void shadow(DrawContext ctx, float x, float y, float w, float h, float spread, float alpha) {
        image(ctx, tex("glow"), x - spread, y - spread * 0.6f, w + spread * 2, h + spread * 2, argb(alpha, 0x000000));
    }

    static void icon(DrawContext ctx, String name, float x, float y, float size, int argb) {
        image(ctx, tex(name), x, y, size, size, argb);
    }

    private static int mixArgb(int a, int b, float t) {
        int alpha = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        return (alpha << 24) | mix(a, b, t);
    }

    // ------------------------------------------------------------------ text

    static Text t(String s, Identifier font) {
        return Text.literal(s).setStyle(Style.EMPTY.withFont(font));
    }

    static int width(TextRenderer tr, String s, Identifier font) {
        return tr.getWidth(t(s, font));
    }

    /** Text at a fractional position. */
    static void text(DrawContext ctx, TextRenderer tr, String s, Identifier font, float x, float y, int argb) {
        if (((argb >>> 24) & 255) < 5) return; // fully transparent text would be drawn opaque
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        ctx.drawText(tr, t(s, font), 0, 0, argb, false);
        m.pop();
    }

    static void centered(DrawContext ctx, TextRenderer tr, String s, Identifier font, float cx, float y, int argb) {
        text(ctx, tr, s, font, cx - width(tr, s, font) / 2f, y, argb);
    }

    static String fit(TextRenderer tr, String s, Identifier font, float maxWidth) {
        if (width(tr, s, font) <= maxWidth) return s;
        while (!s.isEmpty() && width(tr, s + "…", font) > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
