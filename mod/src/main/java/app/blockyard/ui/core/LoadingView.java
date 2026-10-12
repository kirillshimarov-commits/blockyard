package app.blockyard.ui.core;

import java.awt.image.BufferedImage;

/** Loading screens (connecting, world loading, saving), like mockup 4. */
public final class LoadingView {
    private LoadingView() {}

    /** progress 0..1, or below 0 for an indeterminate bar. Returns the y below the bar (for buttons). */
    public static float draw(Frame f, String title, String status, BufferedImage icon, String iconKey, float progress) {
        Canvas c = f.c;
        c.space(f.accent, f.t);
        float size = 42.5f, gapTitle = 4.5f;
        float total = size + gapTitle + Kit.lh(Kit.H2_PAUSE) + 4 + Kit.lh(Kit.BTN) + 2.5f + 1.5f + 3.5f;
        float y = (270 - total) / 2 - 8;
        float x = (f.vw - size) / 2;
        float bob = (float) Math.sin(f.t * 1.6) * 1.2f;
        c.glow(x + size / 2, y + size / 2 + bob, size * 1.3f, size * 1.3f, Kit.a(0.5f, f.accent));
        HomeView.art(f, new HomeView.Entry(iconKey, title, "", java.util.List.of(), 0, null, icon), x, y + bob, size, 9, 1, Kit.TILE);
        y += size + gapTitle;
        Kit.textCenter(c, Fonts.fit(title, Kit.H2_PAUSE, f.vw * 0.8f), Kit.H2_PAUSE, f.vw / 2, y + Kit.lh(Kit.H2_PAUSE) / 2, Kit.WHITE);
        y += Kit.lh(Kit.H2_PAUSE) + 4;
        Kit.textCenter(c, Fonts.fit(status, Kit.BTN, f.vw * 0.8f), Kit.BTN, f.vw / 2, y + Kit.lh(Kit.BTN) / 2, Kit.MUTED);
        y += Kit.lh(Kit.BTN) + 2.5f;
        float bw = 95, bx = (f.vw - bw) / 2;
        c.fillRound(bx, y, bw, 1.5f, 0.75f, Kit.a(0.1f, 0xFFFFFF));
        if (progress >= 0) {
            c.fillRound(bx, y, Math.max(1.5f, bw * Math.min(1, progress)), 1.5f, 0.75f, Kit.WHITE);
        } else {
            float seg = bw * 0.35f, pos = (f.t * 0.55f % 1.35f) - 0.35f;
            float sx = Math.max(bx, bx + pos * bw), ex = Math.min(bx + bw, bx + pos * bw + seg);
            if (ex > sx) c.fillRound(sx, y, ex - sx, 1.5f, 0.75f, Kit.WHITE);
        }
        return y + 1.5f + 3.5f;
    }

    /** The .cancel pill (vanilla buttons are drawn with it at their own places). */
    public static void button(Frame f, String label, float x, float y, float w, float h) {
        boolean hov = f.hover(x, y, w, h);
        f.c.fillRound(x, y, w, h, h / 2, Kit.a(hov ? 0.16f : 0.1f, 0xFFFFFF));
        Kit.textCenter(f.c, label, Kit.BODY, x + w / 2, y + h / 2, Kit.WHITE);
    }
}
