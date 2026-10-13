package app.blockyard.ui.core;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Components shared by the views, transcribed from the approved mockups' CSS
 * (1080 CSS pixels = 270 canvas units, so every CSS pixel value is divided by 4).
 */
public final class Kit {
    public static final int WHITE = 0xFFFFFFFF, INK = 0xFF0B0A12, MUTED = 0xFFA39FB8, TEXT = 0xFFF1EFFA;
    public static final int GREEN = 0x3EE08F;

    public static final Fonts.Style PILL = new Fonts.Style(Fonts.Face.BODY, 4f);        // 16px
    public static final Fonts.Style BODY = new Fonts.Style(Fonts.Face.BODY, 3.75f);     // 15px
    public static final Fonts.Style BODY_B = new Fonts.Style(Fonts.Face.BOLD, 3.75f);
    public static final Fonts.Style CLOCK = new Fonts.Style(Fonts.Face.BOLD, 4f);
    public static final Fonts.Style META = new Fonts.Style(Fonts.Face.BODY, 4.5f);      // 18px
    public static final Fonts.Style TAG = new Fonts.Style(Fonts.Face.BOLD, 3.25f);      // 13px
    public static final Fonts.Style SUB = new Fonts.Style(Fonts.Face.BODY, 3.125f);     // 12.5px
    public static final Fonts.Style LINK = new Fonts.Style(Fonts.Face.BOLD, 3.25f);
    public static final Fonts.Style PLAY = new Fonts.Style(Fonts.Face.BOLD, 5f);        // 20px
    public static final Fonts.Style H1 = new Fonts.Style(Fonts.Face.HEAD, 17f, -0.02f); // 68px, like .hero-title
    public static final Fonts.Style H2 = new Fonts.Style(Fonts.Face.HEAD, 7.5f);        // 30px
    public static final Fonts.Style H2_PAUSE = new Fonts.Style(Fonts.Face.HEAD, 8.5f);  // 34px
    public static final Fonts.Style H2_MODAL = new Fonts.Style(Fonts.Face.HEAD, 7f);    // 28px
    public static final Fonts.Style CARD_TITLE = new Fonts.Style(Fonts.Face.HEAD, 5f);  // 20px
    public static final Fonts.Style GROUP = new Fonts.Style(Fonts.Face.BOLD, 3.25f, 0.08f);
    public static final Fonts.Style SEG = new Fonts.Style(Fonts.Face.BODY, 3.5f);       // 14px
    public static final Fonts.Style SEG_ON = new Fonts.Style(Fonts.Face.BOLD, 3.5f);
    public static final Fonts.Style BTN = new Fonts.Style(Fonts.Face.BODY, 4.25f);      // 17px
    public static final Fonts.Style BTN_B = new Fonts.Style(Fonts.Face.BOLD, 4.25f);
    public static final Fonts.Style TILE = new Fonts.Style(Fonts.Face.BOLD, 8f);        // 32px

    private Kit() {}

    public static int a(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24) | (rgb & 0xFFFFFF);
    }

    public static int withAlpha(int argb, float mul) {
        int al = Math.round(((argb >>> 24) & 255) * mul);
        return (al << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        return SpaceArt.mixRgb(a, b, t);
    }

    /** CSS color-mix(in srgb, accent p%, transparent). */
    public static int accentA(int accent, float p) {
        return a(p, accent);
    }

    public static float lh(Fonts.Style s) {
        return Fonts.lineHeight(s);
    }

    /** Text vertically centered on cy (CSS centers the line box). */
    public static void textMid(Canvas c, String s, Fonts.Style st, float x, float cy, int color) {
        c.text(s, st, x, cy - Fonts.lineHeight(st) / 2, color);
    }

    public static void textCenter(Canvas c, String s, Fonts.Style st, float cx, float cy, int color) {
        c.text(s, st, cx - Fonts.width(s, st) / 2, cy - Fonts.lineHeight(st) / 2, color);
    }

    public static float w(String s, Fonts.Style st) {
        return Fonts.width(s, st);
    }

    /** .card / .panel: glass with a hairline border. */
    public static void glass(Canvas c, float x, float y, float w, float h, float r) {
        c.fillRound(x, y, w, h, r, a(0.045f, 0xFFFFFF), a(0.045f, 0xFFFFFF), 0.25f, a(0.08f, 0xFFFFFF));
    }

    // ------------------------------------------------------------------ top bar

    public interface TabAction {
        void open(int tab);
    }

    /** .topbar: tab pills left, settings / avatar / clock right. Returns the bar's bottom. */
    public static float topBar(Frame f, String[] tabs, int active, boolean settingsOn, String user,
                               TabAction onTab, Runnable onSettings, Runnable onFriend) {
        Canvas c = f.c;
        float pillH = 2.5f * 2 + lh(PILL);
        float boxH = pillH + 1.25f * 2 + 0.5f;
        float y = 7 + 0.25f;
        float x = 10;
        // .pills container
        float total = 1.25f * 2;
        for (String t : tabs) total += w(t, PILL) + 5.5f * 2 + 0.5f;
        total -= 0.5f;
        c.shadow(x, y + 1.5f, total, boxH, boxH / 2, 6, 0.25f);
        c.fillRound(x, y, total, boxH, boxH / 2, a(0.05f, 0xFFFFFF), a(0.05f, 0xFFFFFF), 0.25f, a(0.09f, 0xFFFFFF));
        float px = x + 1.25f + 0.25f, py = y + 1.25f + 0.25f;
        for (int i = 0; i < tabs.length; i++) {
            float pw = w(tabs[i], PILL) + 11;
            boolean on = i == active;
            boolean hov = f.hover(px, py, pw, pillH);
            if (on) {
                c.glow(px + pw / 2, py + pillH / 2 + 1, pw * 0.75f, pillH * 1.1f, a(0.10f, 0xFFFFFF));
                c.fillRound(px, py, pw, pillH, pillH / 2, WHITE);
            } else if (hov) {
                c.fillRound(px, py, pw, pillH, pillH / 2, a(0.06f, 0xFFFFFF));
            }
            textMid(c, tabs[i], PILL, px + 5.5f, py + pillH / 2, on ? INK : a(hov ? 1f : 0.72f, 0xFFFFFF));
            final int target = i;
            f.hit(px, py, pw, pillH, () -> onTab.open(target));
            px += pw + 0.5f;
        }

        // .right
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String day = ", " + cap(LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru")));
        float rw = 1.25f + 11 + 2.5f + 11 + 2.5f + 10 + 2.5f + w(time, CLOCK) + w(day, PILL) + 5;
        float rx = f.vw - 10 - rw;
        float rh = boxH;
        c.fillRound(rx, y, rw, rh, rh / 2, a(0.05f, 0xFFFFFF), a(0.05f, 0xFFFFFF), 0.25f, a(0.09f, 0xFFFFFF));
        float cy = y + rh / 2;
        float bx = rx + 1.25f + 0.25f;
        if (onFriend != null) {
            boolean hovF = f.hover(bx, cy - 5.5f, 11, 11);
            if (hovF) c.disk(bx + 5.5f, cy, 5.5f, a(0.1f, 0xFFFFFF));
            c.icon("users", bx + 2.75f, cy - 2.75f, 5.5f, WHITE);
            f.hit(bx, cy - 5.5f, 11, 11, onFriend);
        } else {
            c.icon("user", bx + 2.75f, cy - 2.75f, 5.5f, WHITE);
        }
        bx += 11 + 2.5f;
        boolean hovG = f.hover(bx, cy - 5.5f, 11, 11);
        if (settingsOn || hovG) c.disk(bx + 5.5f, cy, 5.5f, a(settingsOn ? 0.12f : 0.1f, 0xFFFFFF));
        c.icon("gear", bx + 2.75f, cy - 2.75f, 5.5f, WHITE);
        f.hit(bx, cy - 5.5f, 11, 11, onSettings);
        bx += 11 + 2.5f;
        c.disk(bx + 5, cy, 5, 0xFF000000 | f.accent);
        textCenter(c, user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase(Locale.ROOT), BODY_B, bx + 5, cy, WHITE);
        bx += 10 + 2.5f;
        textMid(c, time, CLOCK, bx, cy, WHITE);
        textMid(c, day, PILL, bx + w(time, CLOCK), cy, MUTED);
        return y + boxH;
    }

    public static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // ------------------------------------------------------------------ buttons

    /** .play-like white pill; returns width. */
    public static float whitePill(Frame f, String label, Fonts.Style st, float x, float y, float padX, float padY, String icon, Runnable action) {
        Canvas c = f.c;
        float iconW = icon == null ? 0 : st.size() * 0.8f + 3;
        float w = w(label, st) + padX * 2 + iconW, h = lh(st) + padY * 2;
        boolean hov = f.hover(x, y, w, h);
        c.glow(x + w / 2, y + h / 2 + 2.5f, w * 0.7f, h * 1.2f, a(hov ? 0.22f : 0.12f, hov ? f.accent : 0xFFFFFF));
        float g = hov ? 0.6f : 0;
        c.fillRound(x - g, y - g, w + g * 2, h + g * 2, (h + g * 2) / 2, WHITE);
        textMid(c, label, st, x + padX, y + h / 2, INK);
        if (icon != null) c.icon(icon, x + padX + w(label, st) + 3, y + h / 2 - st.size() * 0.4f, st.size() * 0.8f, INK);
        f.hit(x, y, w, h, action);
        return w;
    }

    /** Full-width .btn of the pause menu and dialogs. style: 0 glass, 1 white, 2 red. */
    public static void wideBtn(Frame f, String label, float x, float y, float w, int style, Runnable action) {
        Canvas c = f.c;
        Fonts.Style st = style == 1 ? BTN_B : BTN;
        float h = 4 * 2 + lh(st);
        boolean hov = f.hover(x, y, w, h);
        switch (style) {
            case 1 -> {
                c.glow(x + w / 2, y + h / 2 + 2.5f, w * 0.6f, h * 1.3f, a(hov ? 0.5f : 0.4f, f.accent));
                c.fillRound(x, y, w, h, h / 2, WHITE);
                textCenter(c, label, st, x + w / 2, y + h / 2, INK);
            }
            case 2 -> {
                c.fillRound(x, y, w, h, h / 2, a(hov ? 0.24f : 0.16f, 0xEF5B5B));
                textCenter(c, label, st, x + w / 2, y + h / 2, 0xFFFF9A9A);
            }
            default -> {
                c.fillRound(x, y, w, h, h / 2, a(hov ? 0.16f : 0.1f, 0xFFFFFF));
                textCenter(c, label, st, x + w / 2, y + h / 2, WHITE);
            }
        }
        f.hit(x, y, w, h, action);
    }

    public static float wideBtnH(int style) {
        return 8 + lh(style == 1 ? BTN_B : BTN);
    }

    /** .seg: segmented choice; returns its width. */
    public static float segmented(Frame f, String[] names, int on, float x, float cy, java.util.function.IntConsumer pick) {
        Canvas c = f.c;
        float spanH = 2.25f * 2 + lh(SEG);
        float total = 2;
        for (String n : names) total += w(n, n.equals(names[Math.max(0, on)]) ? SEG_ON : SEG) + 9;
        float h = spanH + 2;
        float y = cy - h / 2;
        c.fillRound(x, y, total, h, 3.5f, a(0.07f, 0xFFFFFF));
        float sx = x + 1;
        for (int i = 0; i < names.length; i++) {
            Fonts.Style st = i == on ? SEG_ON : SEG;
            float sw = w(names[i], st) + 9;
            boolean hov = f.hover(sx, y + 1, sw, spanH);
            if (i == on) c.fillRound(sx, y + 1, sw, spanH, 2.5f, WHITE);
            else if (hov) c.fillRound(sx, y + 1, sw, spanH, 2.5f, a(0.07f, 0xFFFFFF));
            textMid(c, names[i], st, sx + 4.5f, cy, i == on ? INK : a(hov ? 0.95f : 0.7f, 0xFFFFFF));
            final int target = i;
            f.hit(sx, y + 1, sw, spanH, () -> pick.accept(target));
            sx += sw;
        }
        return total;
    }

    public static float segWidth(String[] names, int on) {
        float total = 2;
        for (int i = 0; i < names.length; i++) total += w(names[i], i == on ? SEG_ON : SEG) + 9;
        return total;
    }

    /** .toggle 52x30px with a 24px knob. */
    public static void toggle(Canvas c, float x, float cy, boolean on, int accent) {
        float w = 13, h = 7.5f, y = cy - h / 2;
        c.fillRound(x, y, w, h, h / 2, on ? (0xFF000000 | accent) : a(0.15f, 0xFFFFFF));
        if (on) c.glow(x + w / 2, cy, w * 0.9f, h * 1.2f, a(0.25f, accent));
        c.shadow(on ? x + w - 6.75f : x + 0.75f, y + 0.75f + 0.3f, 6, 6, 3, 1.2f, 0.35f);
        c.disk(on ? x + w - 3.75f : x + 3.75f, cy, 3, WHITE);
    }

    /** .slider: track with accent fill and a white knob. */
    public static void slider(Canvas c, float x, float cy, float w, float value, int accent, boolean active) {
        value = Math.max(0, Math.min(1, value));
        c.fillRound(x, cy - 0.85f, w, 1.7f, 0.85f, a(0.14f, 0xFFFFFF));
        c.fillRound(x, cy - 0.85f, Math.max(1.7f, w * value), 1.7f, 0.85f, 0xFF000000 | accent);
        float kx = x + w * value;
        c.glow(kx, cy, active ? 7 : 5, active ? 7 : 5, a(active ? 0.45f : 0.28f, accent));
        c.shadow(kx - 2.75f, cy - 2.75f + 0.5f, 5.5f, 5.5f, 2.75f, 2.5f, 0.4f);
        c.disk(kx, cy, 2.75f, WHITE);
        c.disk(kx, cy, 1.05f, 0xFF000000 | accent);
    }

    public static void dot(Canvas c, float cx, float cy, int rgb) {
        c.glow(cx, cy, 2.5f, 2.5f, a(0.6f, rgb));
        c.disk(cx, cy, 1.125f, 0xFF000000 | rgb);
    }
}
