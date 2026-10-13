package app.blockyard.ui.core;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Settings, laid out like mockup 2: sections on the left, option rows on the right. */
public final class SettingsView {
    public sealed interface Row permits Slider, Toggle, Choice, Button {}

    public record Slider(String label, String sub, DoubleSupplier get, DoubleConsumer set, Supplier<String> value) implements Row {}

    public record Toggle(String label, String sub, BooleanSupplier get, java.util.function.Consumer<Boolean> set) implements Row {}

    public record Choice(String label, String sub, String[] names, IntSupplier get, IntConsumer set) implements Row {}

    public record Button(String label, String sub, String badge, Runnable action) implements Row {}

    public record Group(String title, List<Row> rows) {}

    public record Section(String name, String icon, String lead, List<Group> groups, boolean oneColumn) {}

    public interface Model {
        List<Section> sections();

        int section();

        void section(int i);

        /** In a world: no tab bar, a Done button instead. */
        boolean inGame();

        void done();

        void openTab(int tab);

        String user();
    }

    public static final class State {
        public float scroll;
        public float contentHeight;
        public float viewHeight;
        public Slider dragging;
    }

    private SettingsView() {}

    public static void draw(Frame f, Model m, State s) {
        Canvas c = f.c;
        if (!m.inGame()) {
            c.space(f.accent, f.t);
            Kit.topBar(f, HomeView.TABS, -1, true, m.user(), m::openTab, () -> {}, null);
        } else {
            Kit.textMid(c, "Настройки", Kit.H2, 10, 7 + 6.5f, Kit.WHITE);
            float bw = Kit.w("Готово", Kit.BODY_B) + 12;
            Kit.whitePill(f, "Готово", Kit.BODY_B, f.vw - 10 - bw, 7 + 6.5f - (Kit.lh(Kit.BODY_B) + 5) / 2, 6, 2.5f, null, m::done);
        }
        List<Section> sections = m.sections();
        int cur = Math.max(0, Math.min(m.section(), sections.size() - 1));
        float top = 28, bottom = 270 - 10, left = 10, sideW = 75, gap = 6;
        // .side
        Kit.glass(c, left, top, sideW, bottom - top, 6.5f);
        float iy = top + 3.5f;
        float itH = 3.25f * 2 + Kit.lh(Kit.PILL);
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            boolean on = i == cur;
            boolean hov = f.hover(left + 3.5f, iy, sideW - 7, itH);
            if (on) c.fillRound(left + 3.5f, iy, sideW - 7, itH, 3.5f, Kit.a(0.10f, 0xFFFFFF));
            else if (hov) c.fillRound(left + 3.5f, iy, sideW - 7, itH, 3.5f, Kit.a(0.05f, 0xFFFFFF));
            float sq = 7.5f, sx = left + 3.5f + 4, scy = iy + itH / 2;
            c.fillRound(sx, scy - sq / 2, sq, sq, 2.25f, on ? (0xFF000000 | f.accent) : Kit.a(0.08f, 0xFFFFFF));
            c.icon(sec.icon(), sx + 1.5f, scy - 2.25f, 4.5f, on ? Kit.WHITE : Kit.a(0.8f, 0xFFFFFF));
            Kit.textMid(c, sec.name(), Kit.PILL, sx + sq + 3.5f, scy, on ? Kit.WHITE : Kit.a(hov ? 0.95f : 0.72f, 0xFFFFFF));
            final int target = i;
            f.hit(left + 3.5f, iy, sideW - 7, itH, () -> {
                m.section(target);
                s.scroll = 0;
            });
            iy += itH + 1;
        }
        Kit.textMid(c, "Esc — назад · Blockyard UI", Kit.LINK, left + 7.5f, bottom - 3.5f - Kit.lh(Kit.LINK) / 2, Kit.MUTED);

        // .panel
        float px = left + sideW + gap, pw = f.vw - px - 10;
        Kit.glass(c, px, top, pw, bottom - top, 6.5f);
        Section sec = sections.get(cur);
        float ix = px + 8.5f, iw = pw - 17;
        Kit.textMid(c, sec.name(), Kit.H2, ix, top + 7.5f + Kit.lh(Kit.H2) / 2, Kit.WHITE);
        float leadY = top + 7.5f + Kit.lh(Kit.H2) + 1.5f;
        Kit.textMid(c, sec.lead(), Kit.BODY, ix, leadY + Kit.lh(Kit.BODY) / 2, Kit.MUTED);
        float contentTop = leadY + Kit.lh(Kit.BODY) + 6.5f;
        float viewH = bottom - 4 - contentTop;
        s.viewHeight = viewH;
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, s.contentHeight - viewH)));
        c.clip(px, contentTop, pw, viewH);
        float y0 = contentTop - s.scroll;
        boolean two = !sec.oneColumn() && iw > 230 && sec.groups().size() > 1;
        float colW = two ? (iw - 6.5f) / 2 : iw;
        float[] colY = {y0, y0};
        for (int g = 0; g < sec.groups().size(); g++) {
            int col = two ? g % 2 : 0;
            float gx = ix + col * (colW + 6.5f);
            float gy = colY[col];
            Group group = sec.groups().get(g);
            if (!group.title().isBlank()) {
                c.text(group.title().toUpperCase(), Kit.GROUP, gx, gy, Kit.MUTED);
                gy += Kit.lh(Kit.GROUP) + 3;
            }
            for (Row row : group.rows()) {
                float h = row(f, s, row, gx, gy, colW, contentTop, viewH);
                gy += h + 2;
            }
            colY[col] = gy + 4.5f;
        }
        s.contentHeight = Math.max(colY[0], colY[1]) - y0;
        c.unclip();
        if (s.contentHeight > viewH) {
            float trackH = viewH, thumbH = Math.max(12, trackH * viewH / s.contentHeight);
            float ty = contentTop + (trackH - thumbH) * (s.scroll / (s.contentHeight - viewH));
            c.fillRound(px + pw - 3, ty, 1.2f, thumbH, 0.6f, Kit.a(0.3f, 0xFFFFFF));
        }
    }

    /** One .opt row; returns its height. Regions outside the visible part are not registered. */
    private static float row(Frame f, State s, Row row, float x, float y, float w, float viewTop, float viewH) {
        Canvas c = f.c;
        String label, sub;
        float h;
        // if-chains instead of pattern switches: the 1.20.1 builds compile for Java 17.
        if (row instanceof Slider sl) { label = sl.label(); sub = sl.sub(); }
        else if (row instanceof Toggle t) { label = t.label(); sub = t.sub(); }
        else if (row instanceof Choice ch) { label = ch.label(); sub = ch.sub(); }
        else { Button b = (Button) row; label = b.label(); sub = b.sub(); }
        float labelH = Kit.lh(Kit.PILL) + (sub == null ? 0 : 0.75f + Kit.lh(Kit.SUB));
        float ctrlH = row instanceof Choice ? 2.25f * 2 + Kit.lh(Kit.SEG) + 2 : row instanceof Slider ? 6 : 7.5f;
        h = Math.max(labelH, ctrlH) + 3.5f * 2;
        boolean visible = y + h > viewTop && y < viewTop + viewH;
        boolean hov = visible && f.hover(x, y, w, h);
        // More pronounced than the mockup: a clearer card per option, as asked.
        c.fillRound(x, y, w, h, 4, Kit.a(hov ? 0.075f : 0.05f, 0xFFFFFF), Kit.a(hov ? 0.075f : 0.05f, 0xFFFFFF), 0.25f, Kit.a(hov ? 0.12f : 0.07f, 0xFFFFFF));
        float cy = y + h / 2;
        float ly = cy - labelH / 2;
        float labelMax = w * (row instanceof Slider ? 0.38f : row instanceof Choice ? 0.4f : 0.7f);
        c.text(Fonts.fit(label, Kit.PILL, labelMax), Kit.PILL, x + 4.5f, ly, Kit.WHITE);
        if (sub != null) c.text(Fonts.fit(sub, Kit.SUB, labelMax), Kit.SUB, x + 4.5f, ly + Kit.lh(Kit.PILL) + 0.75f, Kit.MUTED);
        float right = x + w - 4.5f;
        if (row instanceof Slider sl) {
                String v = sl.value().get();
                float vw = Math.max(19, Kit.w(v, Kit.BODY_B) + 6);
                float pillH = Kit.lh(Kit.BODY_B) + 2.5f;
                c.fillRound(right - vw, cy - pillH / 2, vw, pillH, pillH / 2, Kit.a(0.08f, 0xFFFFFF));
                Kit.textCenter(c, v, Kit.BODY_B, right - vw / 2, cy, Kit.WHITE);
                float tw = Math.min(95, w * 0.42f), tx = right - vw - 4 - tw;
                Kit.slider(c, tx, cy, tw, (float) sl.get().getAsDouble(), f.accent, s.dragging == sl || (visible && f.hover(tx - 3, y, tw + 6, h)));
                if (visible) f.dragHit(tx - 3, y, tw + 6, h, mx -> {
                    s.dragging = sl;
                    sl.set().accept(Math.max(0, Math.min(1, (mx - tx) / tw)));
                });
        } else if (row instanceof Toggle t) {
                boolean on = t.get().getAsBoolean();
                Kit.toggle(c, right - 13, cy, on, f.accent);
                if (visible) f.hit(x, y, w, h, () -> t.set().accept(!on));
        } else if (row instanceof Choice ch) {
                int on = ch.get().getAsInt();
                float sw = Kit.segWidth(ch.names(), on);
                int before = f.hits.size();
                Kit.segmented(f, ch.names(), on, right - sw, cy, i -> ch.set().accept(i));
                if (!visible) f.hits.subList(before, f.hits.size()).clear();
        } else if (row instanceof Button b) {
                if (b.badge() != null) {
                    float bw = Kit.w(b.badge(), Kit.BODY_B) + 6, bh = Kit.lh(Kit.BODY_B) + 2.5f;
                    c.fillRound(right - 6 - bw, cy - bh / 2, bw, bh, bh / 2, Kit.WHITE);
                    Kit.textCenter(c, b.badge(), Kit.BODY_B, right - 6 - bw / 2, cy, Kit.INK);
                }
                c.icon("chevron", right - 4.5f, cy - 2.5f, 5, hov ? Kit.WHITE : Kit.MUTED);
                if (visible) f.hit(x, y, w, h, b.action());
        }
        return h;
    }
}
