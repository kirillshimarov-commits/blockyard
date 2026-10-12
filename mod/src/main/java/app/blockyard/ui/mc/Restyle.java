package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Kit;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.text.TextVisitFactory;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The launcher look for every other vanilla screen (create world, statistics, key binds, video
 * settings…): while such a screen draws, its text uses the launcher fonts and its buttons, sliders,
 * fields, check boxes, tabs, lists and background are drawn in the launcher style.
 */
public final class Restyle {
    /** Vanilla text is 9 GUI units per line with the baseline 7 units below the top. */
    static final Fonts.Style TEXT = new Fonts.Style(Fonts.Face.BODY, 8.5f);
    static final Fonts.Style TEXT_BOLD = new Fonts.Style(Fonts.Face.BOLD, 8.5f);
    static final Fonts.Style TITLE = new Fonts.Style(Fonts.Face.HEAD, 10.5f);

    private static final Deque<Boolean> STACK = new ArrayDeque<>();
    private static int active = 0;
    private static int suspended = 0;
    private static Screen current;

    private Restyle() {}

    public static boolean eligible(Screen s) {
        return s != null && !(s instanceof UiScreen) && !(s instanceof HandledScreen<?>) && !(s instanceof ChatScreen)
                && !(s instanceof BookScreen) && !(s instanceof BookEditScreen) && !(s instanceof AbstractSignEditScreen)
                && !(s instanceof CreditsScreen);
    }

    public static void begin(Screen s) {
        boolean on = eligible(s) && RenderSystem.isOnRenderThread();
        STACK.push(on);
        if (on) {
            active++;
            current = s;
        }
    }

    public static void end() {
        if (STACK.isEmpty()) return;
        if (STACK.pop()) active--;
        if (active == 0) current = null;
    }

    public static void suspend() {
        suspended++;
    }

    public static void resume() {
        if (suspended > 0) suspended--;
    }

    public static boolean active() {
        return active > 0 && suspended == 0 && RenderSystem.isOnRenderThread();
    }

    private static McCanvas gui(DrawContext ctx) {
        McCanvas c = new McCanvas(ctx, (float) MinecraftClient.getInstance().getWindow().getScaleFactor());
        c.alpha = RenderSystem.getShaderColor()[3];
        return c;
    }

    private static int accent() {
        return BlockyardUi.accent;
    }

    // ================================================================ text

    private record Run(String text, int color, boolean bold) {}

    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    private static List<Run> runs(Visitor source, int color) {
        int base = opaque(color);
        List<Run> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int[] cur = {base, 0};
        boolean[] bold = {false};
        source.visit((index, style, codePoint) -> {
            TextColor tc = style.getColor();
            int c = tc == null ? base : (base & 0xFF000000) | (tc.getRgb() & 0xFFFFFF);
            boolean b = style.isBold();
            if (sb.length() > 0 && (c != cur[0] || b != bold[0])) {
                out.add(new Run(sb.toString(), cur[0], bold[0]));
                sb.setLength(0);
            }
            cur[0] = c;
            bold[0] = b;
            sb.appendCodePoint(codePoint);
            return true;
        });
        if (sb.length() > 0) out.add(new Run(sb.toString(), cur[0], bold[0]));
        return out;
    }

    private interface Visitor {
        void visit(net.minecraft.text.CharacterVisitor v);
    }

    private static Visitor of(String s) {
        return v -> TextVisitFactory.visitFormatted(s, Style.EMPTY, v);
    }

    private static Visitor of(OrderedText t) {
        return t::accept;
    }

    private static Visitor of(StringVisitable t) {
        return v -> TextVisitFactory.visitFormatted(t, Style.EMPTY, v);
    }

    private static float width(List<Run> runs) {
        float w = 0;
        for (Run r : runs) w += Fonts.width(r.text(), r.bold() ? TEXT_BOLD : TEXT);
        return w;
    }

    public static int width(String s) {
        return s == null ? 0 : (int) Math.ceil(width(runs(of(s), -1)));
    }

    public static int width(OrderedText t) {
        return (int) Math.ceil(width(runs(of(t), -1)));
    }

    public static int width(StringVisitable t) {
        return (int) Math.ceil(width(runs(of(t), -1)));
    }

    public static int drawText(DrawContext ctx, String s, int x, int y, int color) {
        return draw(ctx, runs(of(s), color), x, y);
    }

    public static int drawText(DrawContext ctx, OrderedText t, int x, int y, int color) {
        return draw(ctx, runs(of(t), color), x, y);
    }

    private static int draw(DrawContext ctx, List<Run> runs, int x, int y) {
        float w = width(runs);
        if (runs.isEmpty() || ((runs.get(0).color() >>> 24) & 255) < 4) return x + (int) Math.ceil(w);
        McCanvas c = gui(ctx);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float scale = (float) Math.hypot(m.m00(), m.m01());
        if (scale <= 0) scale = 1;
        // The screen's title in the heading font, still centered where vanilla put it.
        if (current != null && runs.size() == 1 && Math.abs(scale - 1) < 0.001f && runs.get(0).text().equals(current.getTitle().getString())) {
            Run r = runs.get(0);
            float cx = x + w / 2;
            float tw = Fonts.width(r.text(), TITLE);
            c.textAt(r.text(), TITLE, Math.round(cx - tw / 2), y + 8, r.color(), 1);
            return x + (int) Math.ceil(w);
        }
        float tx = x;
        for (Run r : runs) {
            Fonts.Style st = r.bold() ? TEXT_BOLD : TEXT;
            c.textAt(r.text(), st, tx, y + 7, r.color(), scale);
            tx += Fonts.width(r.text(), st);
        }
        return x + (int) Math.ceil(w);
    }

    // ================================================================ widgets

    private static int white(float a) {
        return Kit.a(a, 0xFFFFFF);
    }

    /** Text vertically centered in a box, in GUI units. */
    private static void label(McCanvas c, String s, Fonts.Style st, float x, float cy, int argb) {
        c.textAt(s, st, x, cy + (Fonts.ascent(st) - Fonts.descent(st)) / 2, argb, 1);
    }

    /** A vanilla button (also cycling buttons): a glass pill, "label: value" split in two. */
    public static void button(DrawContext ctx, int x, int y, int w, int h, String message, boolean active, boolean hovered, boolean focused, float alpha) {
        McCanvas c = gui(ctx);
        c.alpha = alpha;
        float px = 1 / c.guiScale();
        boolean hot = active && (hovered || focused);
        if (hot) c.glow(x + w / 2f, y + h / 2f + 2, w * 0.6f, h * 1.3f, Kit.a(0.16f, accent()));
        c.fillRound(x, y, w, h, h / 2f, white(!active ? 0.04f : hot ? 0.17f : 0.09f), white(!active ? 0.04f : hot ? 0.13f : 0.07f),
                px, white(!active ? 0.06f : hot ? 0.3f : 0.12f));
        int text = active ? Kit.WHITE : white(0.4f);
        float cy = y + h / 2f;
        int split = message.indexOf(": ");
        if (split > 0 && split < message.length() - 2) {
            String name = message.substring(0, split), value = message.substring(split + 2);
            float pad = Math.min(10, h / 2f + 2);
            float vw = Fonts.width(value, TEXT_BOLD);
            float avail = w - pad * 2 - vw - 6;
            label(c, Fonts.fit(name, TEXT, Math.max(10, avail)), TEXT, x + pad, cy, active ? white(0.62f) : white(0.3f));
            label(c, Fonts.fit(value, TEXT_BOLD, w - pad * 2), TEXT_BOLD, x + w - pad - Math.min(vw, w - pad * 2), cy, text);
        } else {
            String s = Fonts.fit(message, TEXT, w - 8);
            label(c, s, TEXT, Math.round(x + w / 2f - Fonts.width(s, TEXT) / 2), cy, text);
        }
    }

    /** A tab of a tab bar (create world): a white pill when current, like the launcher's tabs. */
    public static void tab(DrawContext ctx, int x, int y, int w, int h, String message, boolean current, boolean hovered, boolean active) {
        McCanvas c = gui(ctx);
        Fonts.Style st = current ? TEXT_BOLD : TEXT;
        float tw = Fonts.width(message, st);
        float pw = Math.min(w - 2, tw + 18), ph = Math.min(h - 4, 17);
        float px = Math.round(x + (w - pw) / 2), py = Math.round(y + (h - ph) / 2);
        if (current) {
            c.glow(px + pw / 2, py + ph / 2 + 1, pw * 0.75f, ph * 1.1f, white(0.10f));
            c.fillRound(px, py, pw, ph, ph / 2, Kit.WHITE);
        } else if (hovered) {
            c.fillRound(px, py, pw, ph, ph / 2, white(0.07f));
        }
        int color = current ? Kit.INK : white(!active ? 0.35f : hovered ? 1f : 0.72f);
        label(c, message, st, Math.round(px + pw / 2 - tw / 2), py + ph / 2, color);
    }

    /** Highlight of the selected row of a list. */
    public static void selection(DrawContext ctx, int x0, int y0, int x1, int y1) {
        McCanvas c = gui(ctx);
        c.fillRound(x0, y0, x1 - x0, y1 - y0, 4, white(0.08f), white(0.06f), 1 / c.guiScale(), Kit.a(0.7f, accent()));
    }

    private static float sliderX, sliderY, sliderW, sliderH;

    /** GUI sprites replaced by launcher-style shapes; returns false to let vanilla draw it. */
    public static boolean sprite(DrawContext ctx, Identifier id, int x, int y, int w, int h) {
        if (!active() || !"minecraft".equals(id.getNamespace())) return false;
        String p = id.getPath();
        if (!p.startsWith("widget/")) return false;
        McCanvas c;
        switch (p) {
            case "widget/button", "widget/button_highlighted", "widget/button_disabled" -> {
                c = gui(ctx);
                boolean hot = p.endsWith("highlighted"), off = p.endsWith("disabled");
                c.fillRound(x, y, w, h, h / 2f, white(off ? 0.04f : hot ? 0.17f : 0.09f), white(off ? 0.04f : hot ? 0.13f : 0.07f),
                        1 / c.guiScale(), white(off ? 0.06f : hot ? 0.3f : 0.12f));
            }
            case "widget/text_field", "widget/text_field_highlighted" -> {
                c = gui(ctx);
                boolean focus = p.endsWith("highlighted");
                c.fillRound(x, y, w, h, Math.min(5, h / 2f), white(focus ? 0.10f : 0.07f), white(focus ? 0.10f : 0.07f),
                        (focus ? 1.5f : 1) / c.guiScale(), focus ? white(0.55f) : white(0.12f));
            }
            case "widget/slider", "widget/slider_highlighted" -> {
                c = gui(ctx);
                sliderX = x;
                sliderY = y;
                sliderW = w;
                sliderH = h;
                boolean hot = p.endsWith("highlighted");
                c.fillRound(x, y, w, h, Math.min(6, h / 2f), white(hot ? 0.10f : 0.07f), white(hot ? 0.10f : 0.07f),
                        1 / c.guiScale(), white(hot ? 0.22f : 0.1f));
            }
            case "widget/slider_handle", "widget/slider_handle_highlighted" -> {
                c = gui(ctx);
                boolean hot = p.endsWith("highlighted");
                float cx = x + w / 2f;
                boolean inside = sliderW > 0 && cx >= sliderX && cx <= sliderX + sliderW && Math.abs(y - sliderY) < 1;
                float sx = inside ? sliderX : x, sh = inside ? sliderH : h;
                float r = Math.min(6, sh / 2f);
                int a = accent();
                // Filled part in the accent color, then a bright knob.
                c.fillRound(sx, y, Math.max(r * 2, cx - sx + 3), sh, r, Kit.a(0.75f, Kit.mix(a, 0xFFFFFF, 0.12f)), Kit.a(0.6f, a), 0, 0);
                c.glow(cx, y + sh / 2f, hot ? 12 : 9, sh * (hot ? 0.9f : 0.7f), Kit.a(hot ? 0.55f : 0.35f, a));
                c.fillRound(cx - 2, y + 3, 4, sh - 6, 2, Kit.WHITE);
            }
            case "widget/checkbox", "widget/checkbox_highlighted", "widget/checkbox_selected", "widget/checkbox_selected_highlighted" -> {
                c = gui(ctx);
                boolean on = p.contains("selected"), hot = p.endsWith("highlighted");
                float s = Math.min(w, h);
                if (on) {
                    c.glow(x + s / 2f, y + s / 2f, s, s, Kit.a(hot ? 0.45f : 0.3f, accent()));
                    c.fillRound(x, y, s, s, s * 0.28f, 0xFF000000 | Kit.mix(accent(), 0xFFFFFF, 0.1f), 0xFF000000 | accent(), 0, 0);
                    c.icon("check", x + s * 0.15f, y + s * 0.15f, s * 0.7f, Kit.WHITE);
                } else {
                    c.fillRound(x, y, s, s, s * 0.28f, white(hot ? 0.12f : 0.07f), white(hot ? 0.12f : 0.07f),
                            1.5f / c.guiScale(), white(hot ? 0.6f : 0.3f));
                }
            }
            case "widget/scroller" -> {
                c = gui(ctx);
                c.fillRound(x + w / 2f - 1.5f, y, 3, h, 1.5f, white(0.38f));
            }
            case "widget/scroller_background" -> {
                c = gui(ctx);
                c.fillRound(x + w / 2f - 1.5f, y, 3, h, 1.5f, white(0.06f));
            }
            case "widget/tab", "widget/tab_highlighted", "widget/tab_selected", "widget/tab_selected_highlighted" -> {
                c = gui(ctx);
                boolean on = p.contains("selected"), hot = p.endsWith("highlighted");
                float ph = Math.min(h - 4, 17), py = y + (h - ph) / 2f;
                if (on) c.fillRound(x + 2, py, w - 4, ph, ph / 2, 0xFF000000 | accent());
                else if (hot) c.fillRound(x + 2, py, w - 4, ph, ph / 2, white(0.07f));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Textures of the vanilla menu background: dropped, or replaced by a glass band for lists. */
    public static boolean texture(DrawContext ctx, Identifier id, int x, int y, int w, int h) {
        if (!active()) return false;
        String p = id.getPath();
        if (p.contains("menu_list_background")) {
            McCanvas c = gui(ctx);
            c.rect(x, y, w, h, white(0.025f));
            return true;
        }
        if (p.contains("header_separator") || p.contains("footer_separator")) {
            McCanvas c = gui(ctx);
            c.rect(x, y + h / 2f - 0.5f / c.guiScale() * 2, w, 1 / c.guiScale(), white(0.08f));
            return true;
        }
        return p.contains("tab_header_background") || p.endsWith("menu_background.png");
    }

    /** The background of a restyled screen: space in the menus, the blurred game in a world. */
    public static void background(DrawContext ctx, boolean inWorld) {
        McCanvas c = new McCanvas(ctx);
        if (inWorld) c.fillScreen(0x99060510);
        else c.space(accent(), UiScreen.seconds());
    }
}
