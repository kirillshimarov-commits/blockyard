package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Widgets;
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
    private static final Fonts.Style TEXT = Widgets.TEXT, TEXT_BOLD = Widgets.TEXT_BOLD, TITLE = Widgets.TITLE;

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

    /** A vanilla button (also cycling buttons). */
    public static void button(DrawContext ctx, int x, int y, int w, int h, String message, boolean active, boolean hovered, boolean focused, float alpha) {
        McCanvas c = gui(ctx);
        c.alpha = alpha;
        Widgets.button(c, x, y, w, h, message, active, hovered || focused, accent());
    }

    public static void tab(DrawContext ctx, int x, int y, int w, int h, String message, boolean current, boolean hovered, boolean active) {
        Widgets.tab(gui(ctx), x, y, w, h, message, current, hovered, active);
    }

    public static void slider(DrawContext ctx, int x, int y, int w, int h, String message, double value, boolean active, boolean hot, float alpha) {
        McCanvas c = gui(ctx);
        c.alpha = alpha;
        Widgets.slider(c, x, y, w, h, message, (float) value, active, hot, accent());
    }

    public static void tabBar(DrawContext ctx, int x0, int y0, int x1, int y1) {
        Widgets.tabBar(gui(ctx), x0, y0, x1, y1);
    }

    public static void selection(DrawContext ctx, int x0, int y0, int x1, int y1) {
        Widgets.selection(gui(ctx), x0, y0, x1, y1, accent());
    }

    private static float sliderX, sliderY, sliderW, sliderH;

    /** GUI sprites replaced by launcher-style shapes; returns false to let vanilla draw it. */
    public static boolean sprite(DrawContext ctx, Identifier id, int x, int y, int w, int h) {
        if (!active() || !"minecraft".equals(id.getNamespace())) return false;
        String p = id.getPath();
        if (!p.startsWith("widget/")) return false;
        boolean hot = p.endsWith("highlighted");
        switch (p) {
            case "widget/button", "widget/button_highlighted", "widget/button_disabled" ->
                    Widgets.buttonShape(gui(ctx), x, y, w, h, !p.endsWith("disabled"), hot);
            case "widget/text_field", "widget/text_field_highlighted" -> Widgets.textField(gui(ctx), x, y, w, h, hot);
            case "widget/slider", "widget/slider_highlighted" -> {
                sliderX = x;
                sliderY = y;
                sliderW = w;
                sliderH = h;
                Widgets.sliderTrack(gui(ctx), x, y, w, h, hot);
            }
            case "widget/slider_handle", "widget/slider_handle_highlighted" -> {
                float cx = x + w / 2f;
                boolean inside = sliderW > 0 && cx >= sliderX && cx <= sliderX + sliderW && Math.abs(y - sliderY) < 1;
                Widgets.sliderFill(gui(ctx), inside ? sliderX : x, y, inside ? sliderH : h, cx, hot, accent());
            }
            case "widget/checkbox", "widget/checkbox_highlighted", "widget/checkbox_selected", "widget/checkbox_selected_highlighted" ->
                    Widgets.checkbox(gui(ctx), x, y, Math.min(w, h), p.contains("selected"), hot, accent());
            case "widget/scroller" -> Widgets.scroller(gui(ctx), x, y, w, h, false);
            case "widget/scroller_background" -> Widgets.scroller(gui(ctx), x, y, w, h, true);
            case "widget/tab", "widget/tab_highlighted", "widget/tab_selected", "widget/tab_selected_highlighted" ->
                    Widgets.tabShape(gui(ctx), x, y, w, h, p.contains("selected"), hot, accent());
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
            Widgets.listBand(gui(ctx), x, y, w, h);
            return true;
        }
        if (p.contains("header_separator") || p.contains("footer_separator")) {
            Widgets.separator(gui(ctx), x, y, w, h);
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
