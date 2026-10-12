package app.blockyard.ui.core;

/**
 * Vanilla widgets (buttons, sliders, fields, check boxes, tabs, lists) drawn in the launcher style.
 * Coordinates are vanilla GUI units: the canvas is made with one unit per GUI pixel.
 */
public final class Widgets {
    /** Vanilla text is 9 GUI units per line with the baseline 7 units below the top. */
    public static final Fonts.Style TEXT = new Fonts.Style(Fonts.Face.BODY, 8.5f);
    public static final Fonts.Style TEXT_BOLD = new Fonts.Style(Fonts.Face.BOLD, 8.5f);
    public static final Fonts.Style TITLE = new Fonts.Style(Fonts.Face.HEAD, 10.5f);

    private Widgets() {}

    private static int white(float a) {
        return Kit.a(a, 0xFFFFFF);
    }

    /** One screen pixel in units. */
    private static float px(Canvas c) {
        return 1 / c.ppu();
    }

    /** Text by its baseline. */
    public static void text(Canvas c, String s, Fonts.Style st, float x, float baseline, int argb) {
        c.text(s, st, x, baseline - Fonts.ascent(st), argb);
    }

    /** Text vertically centered on cy. */
    public static void label(Canvas c, String s, Fonts.Style st, float x, float cy, int argb) {
        text(c, s, st, x, cy + (Fonts.ascent(st) - Fonts.descent(st)) / 2, argb);
    }

    /** Button background: a glass pill. */
    public static void buttonShape(Canvas c, float x, float y, float w, float h, boolean active, boolean hot) {
        c.fillRound(x, y, w, h, h / 2f, white(!active ? 0.04f : hot ? 0.17f : 0.09f), white(!active ? 0.04f : hot ? 0.13f : 0.07f),
                px(c), white(!active ? 0.06f : hot ? 0.3f : 0.12f));
    }

    /** A button with its label; "name: value" (cycling buttons) is laid out as name left, value right. */
    public static void button(Canvas c, float x, float y, float w, float h, String message, boolean active, boolean hot, int accent) {
        hot = active && hot;
        if (hot) c.glow(x + w / 2f, y + h / 2f + 2, w * 0.6f, h * 1.3f, Kit.a(0.16f, accent));
        buttonShape(c, x, y, w, h, active, hot);
        int text = active ? Kit.WHITE : white(0.4f);
        float cy = y + h / 2f;
        int split = message.indexOf(": ");
        if (split > 0 && split < message.length() - 2) {
            String name = message.substring(0, split), value = message.substring(split + 2);
            float pad = Math.min(10, h / 2f + 2);
            float vw = Math.min(Fonts.width(value, TEXT_BOLD), w - pad * 2);
            float avail = w - pad * 2 - vw - 6;
            label(c, Fonts.fit(name, TEXT, Math.max(10, avail)), TEXT, x + pad, cy, active ? white(0.62f) : white(0.3f));
            label(c, Fonts.fit(value, TEXT_BOLD, w - pad * 2), TEXT_BOLD, x + w - pad - vw, cy, text);
        } else {
            String s = Fonts.fit(message, TEXT, w - 8);
            label(c, s, TEXT, Math.round(x + w / 2f - Fonts.width(s, TEXT) / 2), cy, text);
        }
    }

    /** A tab: a white pill when current, like the launcher's tab bar. */
    public static void tab(Canvas c, float x, float y, float w, float h, String message, boolean current, boolean hot, boolean active) {
        Fonts.Style st = current ? TEXT_BOLD : TEXT;
        float tw = Fonts.width(message, st);
        float pw = Math.min(w - 2, tw + 18), ph = Math.min(h - 4, 17);
        float px = Math.round(x + (w - pw) / 2), py = Math.round(y + (h - ph) / 2);
        if (current) {
            c.glow(px + pw / 2, py + ph / 2 + 1, pw * 0.75f, ph * 1.1f, white(0.10f));
            c.fillRound(px, py, pw, ph, ph / 2, Kit.WHITE);
        } else if (hot) {
            c.fillRound(px, py, pw, ph, ph / 2, white(0.07f));
        }
        int color = current ? Kit.INK : white(!active ? 0.35f : hot ? 1f : 0.72f);
        label(c, message, st, Math.round(px + pw / 2 - tw / 2), py + ph / 2, color);
    }

    public static void selection(Canvas c, float x0, float y0, float x1, float y1, int accent) {
        c.fillRound(x0, y0, x1 - x0, y1 - y0, 4, white(0.08f), white(0.06f), px(c), Kit.a(0.7f, accent));
    }

    public static void textField(Canvas c, float x, float y, float w, float h, boolean focus) {
        c.fillRound(x, y, w, h, Math.min(5, h / 2f), white(focus ? 0.10f : 0.07f), white(focus ? 0.10f : 0.07f),
                (focus ? 1.5f : 1) * px(c), focus ? white(0.55f) : white(0.12f));
    }

    public static void sliderTrack(Canvas c, float x, float y, float w, float h, boolean hot) {
        c.fillRound(x, y, w, h, Math.min(6, h / 2f), white(hot ? 0.10f : 0.07f), white(hot ? 0.10f : 0.07f), px(c), white(hot ? 0.22f : 0.1f));
    }

    /** The filled part of a slider up to the knob at cx, and the knob. */
    public static void sliderFill(Canvas c, float sx, float y, float h, float cx, boolean hot, int accent) {
        float r = Math.min(6, h / 2f);
        c.fillRound(sx, y, Math.max(r * 2, cx - sx + 3), h, r, Kit.a(0.75f, Kit.mix(accent, 0xFFFFFF, 0.12f)), Kit.a(0.6f, accent), 0, 0);
        c.glow(cx, y + h / 2f, hot ? 12 : 9, h * (hot ? 0.9f : 0.7f), Kit.a(hot ? 0.55f : 0.35f, accent));
        c.fillRound(cx - 2, y + 3, 4, h - 6, 2, Kit.WHITE);
    }

    public static void checkbox(Canvas c, float x, float y, float s, boolean on, boolean hot, int accent) {
        if (on) {
            c.glow(x + s / 2f, y + s / 2f, s, s, Kit.a(hot ? 0.45f : 0.3f, accent));
            c.fillRound(x, y, s, s, s * 0.28f, 0xFF000000 | Kit.mix(accent, 0xFFFFFF, 0.1f), 0xFF000000 | accent, 0, 0);
            c.icon("check", x + s * 0.15f, y + s * 0.15f, s * 0.7f, Kit.WHITE);
        } else {
            c.fillRound(x, y, s, s, s * 0.28f, white(hot ? 0.12f : 0.07f), white(hot ? 0.12f : 0.07f), 1.5f * px(c), white(hot ? 0.6f : 0.3f));
        }
    }

    public static void scroller(Canvas c, float x, float y, float w, float h, boolean track) {
        c.fillRound(x + w / 2f - 1.5f, y, 3, h, 1.5f, white(track ? 0.06f : 0.38f));
    }

    /** Fallback for tab sprites drawn without the tab widget. */
    public static void tabShape(Canvas c, float x, float y, float w, float h, boolean on, boolean hot, int accent) {
        float ph = Math.min(h - 4, 17), py = y + (h - ph) / 2f;
        if (on) c.fillRound(x + 2, py, w - 4, ph, ph / 2, 0xFF000000 | accent);
        else if (hot) c.fillRound(x + 2, py, w - 4, ph, ph / 2, white(0.07f));
    }

    /** The area behind a list: a faint glass band. */
    public static void listBand(Canvas c, float x, float y, float w, float h) {
        c.fillRound(x, y, w, h, 0, white(0.025f));
    }

    /** Header and footer separators: a hairline. */
    public static void separator(Canvas c, float x, float y, float w, float h) {
        c.fillRound(x, y + h / 2f - px(c), w, px(c), 0, white(0.08f));
    }
}
