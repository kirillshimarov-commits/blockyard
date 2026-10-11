package app.blockyard.ui.screen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

/** Small drawing helpers: rounded rectangles, scaled text, colors. */
final class Draw {
    private Draw() {}

    static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Mixes two RGB colors; t = 0 gives a, 1 gives b. */
    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Filled rectangle with rounded corners, drawn as horizontal strips. */
    static void round(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
        ctx.fill(x, y + r, x + w, y + h - r, color);
    }

    static void text(DrawContext ctx, TextRenderer font, String s, float x, float y, float scale, int color) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        ctx.drawText(font, s, 0, 0, color, false);
        m.pop();
    }

    static String ellipsize(TextRenderer font, String s, int maxWidth) {
        if (font.getWidth(s) <= maxWidth) return s;
        String dots = "…";
        while (!s.isEmpty() && font.getWidth(s + dots) > maxWidth) s = s.substring(0, s.length() - 1);
        return s + dots;
    }
}
