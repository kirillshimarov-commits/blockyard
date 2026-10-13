package app.blockyard.ui.core;

import java.awt.image.BufferedImage;
import java.util.List;

/** The main menu, laid out exactly like mockup 1 (and the launcher's home). */
public final class HomeView {
    public static final String[] TABS = {"Одиночная игра", "Сетевая игра", "Ресурспаки", "Моды"};
    public static final int SINGLE = 0, MULTI = 1, PACKS = 2, MODS = 3;

    public record Entry(String key, String name, String detail, List<String> chips, int dot, String motd, BufferedImage icon) {}

    public record Mod(String id, String name, String version, String description, BufferedImage icon) {}

    public record Shot(String key, BufferedImage image) {}

    public record MenuItem(String label, boolean danger, Runnable action) {}

    /** Everything the view needs; implemented by the game and by the preview. */
    public interface Model {
        int tab();

        void openTab(int tab);

        List<Entry> entries();

        /** -1 while worlds are still loading. */
        int selected();

        void select(int i);

        void play(int i);

        void add();

        void settings();

        void friend();

        String user();

        List<Mod> mods();

        List<Shot> shots();

        int shotCount();

        void openShots();

        void playAndInvite();

        List<MenuItem> menu();

        boolean loading();
    }

    private HomeView() {}

    public static final class State {
        public boolean menuOpen;
        public float menuX, menuY;
        public float modsScroll;
        public long changedAt = System.currentTimeMillis();
    }

    public static void draw(Frame f, Model m, State s) {
        Canvas c = f.c;
        c.space(f.accent, f.t);
        Kit.topBar(f, TABS, m.tab(), false, m.user(), m::openTab, m::settings, m::friend);
        if (m.tab() == MODS) {
            drawMods(f, m, s);
        } else {
            List<Entry> list = m.entries();
            int sel = list.isEmpty() ? -1 : Math.max(0, Math.min(m.selected(), list.size() - 1));
            float appear = ease((System.currentTimeMillis() - s.changedAt) / 380f);
            if (sel >= 0) backdrop(f, list.get(sel), appear);
            float captionX = tiles(f, m, list, sel);
            if (sel >= 0) {
                caption(f, list.get(sel), m.tab(), captionX);
                hero(f, m, list.get(sel), sel, appear, s);
            } else {
                empty(f, m);
            }
            shelf(f, m);
        }
        if (s.menuOpen) menu(f, m, s);
    }

    public static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** .backdrop: the selected picture, huge and blurred, behind the hero on the right. */
    private static void backdrop(Frame f, Entry e, float a) {
        if (e.icon() == null) return;
        // width 62%, height 75%, opacity .38, blur(60px), radial mask: drawn as a soft colored glow.
        float w = f.vw * 0.62f, h = 270 * 0.75f;
        int avg = average(e.icon());
        f.c.glow(f.vw - w * 0.30f, h * 0.45f, w * 0.42f, h * 0.48f, Kit.a(0.38f * a, avg));
    }

    static int average(BufferedImage img) {
        long r = 0, g = 0, b = 0, n = 0;
        int step = Math.max(1, img.getWidth() / 16);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int p = img.getRGB(x, y);
                if ((p >>> 24) < 128) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                n++;
            }
        }
        if (n == 0) return 0x8B5CFF;
        // saturate(1.4) a little
        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
        int avg = (rr + gg + bb) / 3;
        rr = clamp(avg + (rr - avg) * 1.4f);
        gg = clamp(avg + (gg - avg) * 1.4f);
        bb = clamp(avg + (bb - avg) * 1.4f);
        return (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    // ------------------------------------------------------------------ tiles

    /** .tiles: kind tile, entries, add tile. Returns the x of the selected tile (for the caption). */
    private static float tiles(Frame f, Model m, List<Entry> list, int sel) {
        Canvas c = f.c;
        float x = 10, cy = 45, gap = 4;
        float actS = 16, base = 21, big = 30;
        // Scroll so the selected tile stays visible.
        float selX = x + actS + gap + sel * (base + gap);
        float scroll = Math.max(0, selX + big + 30 - f.vw);
        x -= scroll;

        c.fillRound(x, cy - actS / 2, actS, actS, 4.5f, Kit.a(0.08f, 0xFFFFFF));
        c.icon(m.tab() == MULTI ? "globe" : "cube", x + 4.25f, cy - 3.75f, 7.5f, Kit.WHITE);
        x += actS + gap;

        float captionX = x;
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            boolean on = i == sel;
            float size = on ? big : base, r = on ? 6.5f : 5;
            float ty = cy - size / 2;
            boolean hov = f.hover(x, ty, size, size);
            if (on) {
                captionX = x;
                // box-shadow: 0 0 0 4px #fff, 0 0 0 9px rgba(255,255,255,.12), 0 18px 50px accent 50%
                c.glow(x + size / 2, ty + size / 2 + 4.5f, size * 0.95f, size * 0.95f, Kit.a(0.5f, f.accent));
                c.fillRound(x - 2.25f, ty - 2.25f, size + 4.5f, size + 4.5f, r + 2.25f, Kit.a(0.12f, 0xFFFFFF));
                c.fillRound(x - 1, ty - 1, size + 2, size + 2, r + 1, Kit.WHITE);
            }
            art(f, e, x, hov && !on ? ty - 0.75f : ty, size, r, on || hov ? 1 : 0.78f, Kit.TILE);
            final int target = i;
            f.hit(x, ty, size, size, () -> {
                if (m.selected() == target) m.play(target);
                else m.select(target);
            });
            x += size + gap;
        }
        boolean hovAdd = f.hover(x, cy - actS / 2, actS, actS);
        c.fillRound(x, cy - actS / 2, actS, actS, 4.5f, Kit.a(hovAdd ? 0.08f : 0, 0xFFFFFF), Kit.a(hovAdd ? 0.08f : 0, 0xFFFFFF),
                0.375f, Kit.a(0.22f, 0xFFFFFF));
        c.icon("plus", x + 4.75f, cy - 3.25f, 6.5f, Kit.WHITE);
        f.hit(x, cy - actS / 2, actS, actS, m::add);
        return captionX;
    }

    /** A world/server picture, or its first letter on the accent gradient. */
    static void art(Frame f, Entry e, float x, float y, float size, float r, float alpha, Fonts.Style letterStyle) {
        Canvas c = f.c;
        if (e.icon() != null) {
            c.picture(e.key(), e.icon(), x, y, size, size, r, alpha);
        } else {
            c.fillRound(x, y, size, size, r, Kit.a(alpha, Kit.mix(f.accent, 0xFFFFFF, 0.12f)), Kit.a(alpha, Kit.mix(f.accent, 0x000000, 0.3f)), 0, 0);
            String letter = e.name().isEmpty() ? "?" : e.name().substring(0, 1).toUpperCase();
            Fonts.Style st = new Fonts.Style(Fonts.Face.BOLD, size * 0.38f);
            Kit.textCenter(c, letter, st, x + size / 2, y + size / 2, Kit.a(alpha, 0xFFFFFF));
        }
    }

    private static void caption(Frame f, Entry e, int tab, float x) {
        Canvas c = f.c;
        float cy = 65.5f + Kit.lh(Kit.META) / 2;
        String tag = tab == MULTI ? "сервер" : "мир";
        float tw = Kit.w(tag, Kit.TAG) + 5, th = Kit.lh(Kit.TAG) + 1;
        c.fillRound(x, cy - th / 2, tw, th, th / 2, Kit.WHITE);
        Kit.textMid(c, tag, Kit.TAG, x + 2.5f, cy, Kit.INK);
        Kit.textMid(c, Fonts.fit(e.name(), Kit.META, f.vw * 0.4f), Kit.META, x + tw + 2.5f, cy, Kit.WHITE);
    }

    // ------------------------------------------------------------------ hero

    private static void hero(Frame f, Model m, Entry e, int sel, float a, State s) {
        Canvas c = f.c;
        float lift = (1 - a) * 5;
        // .art
        float art = 80, ax = f.vw - 17.5f - art, ay = 75 - lift * 0.5f;
        c.glow(ax + art / 2, ay + art / 2, art * 0.95f, art * 0.95f, Kit.a(0.35f * a, f.accent));
        c.shadow(ax, ay + 7.5f, art, art, 8.5f, 20, 0.55f * a);
        art(f, e, ax, ay, art, 8.5f, a, Kit.TILE);
        c.fillRound(ax, ay, art, art, 8.5f, 0, 0, 0.25f, Kit.a(0.08f * a, 0xFFFFFF));

        float left = 17.5f, maxW = ax - left - 20;
        float y = 79.5f + lift;
        // h1: 64px/1.1
        float h1Box = 16 * 1.1f;
        Kit.textMid(c, Fonts.fit(e.name(), Kit.H1, maxW), Kit.H1, left, y + h1Box / 2, Kit.a(a, 0xFFFFFF));
        y += h1Box + 3;
        Kit.textMid(c, Fonts.fit(e.detail(), Kit.META, maxW), Kit.META, left, y + Kit.lh(Kit.META) / 2, Kit.withAlpha(Kit.MUTED, a));
        y += Kit.lh(Kit.META) + 4;
        float chipH = 2 * 2 + Kit.lh(Kit.BODY);
        float cx = left;
        for (int i = 0; i < e.chips().size(); i++) {
            String chip = e.chips().get(i);
            boolean dot = i == 0;
            float cw = Kit.w(chip, Kit.BODY) + 8 + (dot ? 2.25f + 2.25f : 0);
            c.fillRound(cx, y, cw, chipH, 3, Kit.a(0.08f * a, 0xFFFFFF));
            float tx = cx + 4;
            if (dot) {
                Kit.dot(c, cx + 4 + 1.125f, y + chipH / 2, e.dot());
                tx += 2.25f + 2.25f;
            }
            Kit.textMid(c, chip, Kit.BODY, tx, y + chipH / 2, Kit.a(a, 0xFFFFFF));
            cx += cw + 2.5f;
        }
        y += chipH;
        if (e.motd() != null && !e.motd().isBlank()) {
            Kit.textMid(c, Fonts.fit(e.motd(), Kit.BODY, maxW), Kit.BODY, left, y + 3 + Kit.lh(Kit.BODY) / 2, Kit.withAlpha(Kit.MUTED, 0.8f * a));
        }
        // .actions
        float by = 79.5f + h1Box + 3 + Kit.lh(Kit.META) + 4 + chipH + 7 + lift;
        float pw = Kit.whitePill(f, "Играть", Kit.PLAY, left, by, 10, 4.5f, "play", () -> m.play(sel));
        float ph = Kit.lh(Kit.PLAY) + 9;
        float mx = left + pw + 3.5f, md = 15;
        boolean hov = f.hover(mx, by + ph / 2 - md / 2, md, md);
        c.disk(mx + md / 2, by + ph / 2, md / 2, Kit.a(hov ? 0.18f : 0.1f, 0xFFFFFF));
        for (int d = -1; d <= 1; d++) c.disk(mx + md / 2 + d * 2.3f, by + ph / 2, 0.7f, Kit.WHITE);
        f.hit(mx, by + ph / 2 - md / 2, md, md, () -> {
            s.menuOpen = true;
            f.modal = true;
            s.menuX = mx;
            s.menuY = by + ph / 2 - md / 2;
        });
    }

    private static void empty(Frame f, Model m) {
        Canvas c = f.c;
        float left = 17.5f, y = 90;
        String title = m.tab() == MULTI ? "Серверов пока нет" : m.loading() ? "Загружаем миры…" : "Миров пока нет";
        Kit.textMid(c, title, Kit.H1, left, y, Kit.WHITE);
        if (m.loading()) return;
        Kit.textMid(c, m.tab() == MULTI ? "Добавьте сервер по IP-адресу" : "Создайте свой первый мир", Kit.META, left, y + 14, Kit.MUTED);
        Kit.whitePill(f, m.tab() == MULTI ? "Добавить сервер" : "Создать мир", Kit.PLAY, left, y + 24, 10, 4.5f, "plus", m::add);
    }

    // ------------------------------------------------------------------ shelf

    private static void shelf(Frame f, Model m) {
        float left = 17.5f, gap = 5, h = 81, top = 270 - 12.5f - h;
        float w = (f.vw - left * 2 - gap * 2) / 3;
        for (int i = 0; i < 3; i++) {
            float x = left + i * (w + gap);
            Kit.glass(f.c, x, top, w, h, 6);
            switch (i) {
                case 0 -> modsCard(f, m, x, top, w, h);
                case 1 -> shotsCard(f, m, x, top, w, h);
                default -> friendCard(f, m, x, top, w, h);
            }
        }
    }

    private static float header(Frame f, String title, String right, float x, float y, float w) {
        float cy = y + 5 + Kit.lh(Kit.BODY_B) / 2;
        Kit.textMid(f.c, title, Kit.BODY_B, x + 5.5f, cy, Kit.WHITE);
        if (right != null) Kit.textMid(f.c, right, Kit.BODY, x + w - 5.5f - Kit.w(right, Kit.BODY), cy, Kit.MUTED);
        return y + 5 + Kit.lh(Kit.BODY_B) + 3;
    }

    private static void link(Frame f, String label, float x, float bottom, Runnable action) {
        float lh = Kit.lh(Kit.LINK), y = bottom - 4 - lh;
        boolean hov = f.hover(x, y, Kit.w(label, Kit.LINK), lh);
        f.c.text(label, Kit.LINK, x, y, hov ? Kit.WHITE : Kit.MUTED);
        f.hit(x, y, Kit.w(label, Kit.LINK), lh, action);
    }

    private static void modsCard(Frame f, Model m, float x, float y, float w, float h) {
        Canvas c = f.c;
        List<Mod> mods = m.mods();
        float ry = header(f, "Моды в сборке", String.valueOf(mods.size()), x, y, w);
        for (int i = 0; i < Math.min(4, mods.size()); i++) {
            Mod mod = mods.get(i);
            modIcon(f, mod, x + 5.5f, ry, 9.5f);
            Kit.textMid(c, Fonts.fit(mod.name(), Kit.BODY, w - 45), Kit.BODY, x + 5.5f + 9.5f + 3, ry + 4.75f, Kit.WHITE);
            String v = Fonts.fit(mod.version(), Kit.SUB, 26);
            Kit.textMid(c, v, Kit.SUB, x + w - 5.5f - Kit.w(v, Kit.SUB), ry + 4.75f, Kit.MUTED);
            ry += 9.5f + 3;
        }
        link(f, "Все моды ›", x + 5.5f, y + h, () -> m.openTab(MODS));
    }

    static void modIcon(Frame f, Mod mod, float x, float y, float s) {
        if (mod.icon() != null) {
            f.c.picture("mod:" + mod.id(), mod.icon(), x, y, s, s, s * 0.29f, 1);
        } else {
            int col = Kit.mix(f.accent, (mod.id().hashCode() & 0xFFFFFF) | 0x404040, 0.45f);
            f.c.fillRound(x, y, s, s, s * 0.29f, 0xFF000000 | col);
            Kit.textCenter(f.c, mod.name().substring(0, 1).toUpperCase(), new Fonts.Style(Fonts.Face.BOLD, s * 0.45f), x + s / 2, y + s / 2, Kit.WHITE);
        }
    }

    private static void shotsCard(Frame f, Model m, float x, float y, float w, float h) {
        Canvas c = f.c;
        float ty = header(f, "Скриншоты", String.valueOf(m.shotCount()), x, y, w);
        List<Shot> shots = m.shots();
        float gap = 2, tw = (w - 11 - gap * 2) / 3, th = tw * 10 / 16;
        if (shots.isEmpty()) Kit.textMid(c, "Пока пусто — нажмите F2 в игре", Kit.BODY, x + 5.5f, ty + 4, Kit.MUTED);
        for (int i = 0; i < Math.min(6, shots.size()); i++) {
            float sx = x + 5.5f + (i % 3) * (tw + gap), sy = ty + (i / 3) * (th + gap);
            Shot shot = shots.get(i);
            if (shot.image() != null) c.picture(shot.key(), shot.image(), sx, sy, tw, th, 2.5f, 1);
            else c.fillRound(sx, sy, tw, th, 2.5f, Kit.a(0.06f, 0xFFFFFF));
        }
        link(f, "Открыть папку ›", x + 5.5f, y + h, m::openShots);
    }

    private static void friendCard(Frame f, Model m, float x, float y, float w, float h) {
        Canvas c = f.c;
        float ty = header(f, "Игра с другом", "Blockyard", x, y, w);
        Kit.textMid(c, "Открой мир для друга", Kit.CARD_TITLE, x + 5.5f, ty + Kit.lh(Kit.CARD_TITLE) / 2, Kit.WHITE);
        ty += Kit.lh(Kit.CARD_TITLE) + 1.5f;
        String[] lines = {"Друг подключится по коду-приглашению", "прямо из меню паузы — без Hamachi и Radmin"};
        for (String line : lines) {
            Kit.textMid(c, Fonts.fit(line, Kit.BODY, w - 11), Kit.BODY, x + 5.5f, ty + Kit.lh(Kit.BODY) / 2, Kit.MUTED);
            ty += Kit.lh(Kit.BODY);
        }
        if (m.tab() == SINGLE && !m.entries().isEmpty()) {
            float bh = Kit.lh(Kit.BODY_B) + 6;
            Kit.whitePill(f, "Пригласить", Kit.BODY_B, x + 5.5f, y + h - 4 - bh, 6, 3, null, m::playAndInvite);
        }
    }

    // ------------------------------------------------------------------ mods tab

    private static void drawMods(Frame f, Model m, State s) {
        Canvas c = f.c;
        float left = 17.5f, top = 32;
        Kit.textMid(c, "Моды", Kit.H1, left, top + 8.8f, Kit.WHITE);
        Kit.textMid(c, m.mods().size() + " в этой сборке · добавляйте и удаляйте моды в лаунчере", Kit.META, left, top + 22, Kit.MUTED);
        int cols = f.vw > 380 ? 3 : 2;
        float gap = 5, w = (f.vw - left * 2 - gap * (cols - 1)) / cols, h = 22;
        float listTop = top + 30, listH = 270 - listTop - 10;
        List<Mod> mods = m.mods();
        float maxScroll = Math.max(0, (float) Math.ceil(mods.size() / (double) cols) * (h + gap) - listH);
        s.modsScroll = Math.max(0, Math.min(s.modsScroll, maxScroll));
        c.clip(0, listTop, f.vw, listH);
        for (int i = 0; i < mods.size(); i++) {
            float x = left + (i % cols) * (w + gap), y = listTop + (i / cols) * (h + gap) - s.modsScroll;
            if (y + h < listTop || y > listTop + listH) continue;
            Mod mod = mods.get(i);
            Kit.glass(c, x, y, w, h, 5);
            modIcon(f, mod, x + 4, y + 3.5f, 15);
            Kit.textMid(c, Fonts.fit(mod.name(), Kit.BODY_B, w - 50), Kit.BODY_B, x + 23, y + 7.5f, Kit.WHITE);
            String v = Fonts.fit(mod.version(), Kit.SUB, 24);
            Kit.textMid(c, v, Kit.SUB, x + w - 4.5f - Kit.w(v, Kit.SUB), y + 7.5f, Kit.MUTED);
            String d = mod.description().isBlank() ? mod.id() : mod.description().replace('\n', ' ');
            Kit.textMid(c, Fonts.fit(d, Kit.SUB, w - 30), Kit.SUB, x + 23, y + 14.5f, Kit.MUTED);
        }
        c.unclip();
    }

    // ------------------------------------------------------------------ menu

    private static void menu(Frame f, Model m, State s) {
        Canvas c = f.c;
        List<MenuItem> items = m.menu();
        float w = 0;
        for (MenuItem it : items) w = Math.max(w, Kit.w(it.label(), Kit.BODY));
        w += 16;
        float rowH = Kit.lh(Kit.BODY) + 5, h = items.size() * rowH + 3;
        float x = s.menuX, y = Math.max(4, s.menuY - h - 3);
        c.shadow(x, y + 3, w, h, 4, 10, 0.6f);
        c.fillRound(x, y, w, h, 4, 0xF4191726, 0xF4191726, 0.25f, Kit.a(0.1f, 0xFFFFFF));
        for (int i = 0; i < items.size(); i++) {
            MenuItem it = items.get(i);
            float ry = y + 1.5f + i * rowH;
            boolean hov = f.hoverPopup(x, ry, w, rowH);
            if (hov) c.fillRound(x + 1.5f, ry, w - 3, rowH, 2.5f, Kit.a(0.1f, 0xFFFFFF));
            Kit.textMid(c, it.label(), Kit.BODY, x + 8, ry + rowH / 2, it.danger() ? 0xFFFF9A9A : Kit.WHITE);
            f.popupHit(x, ry, w, rowH, () -> {
                s.menuOpen = false;
                it.action().run();
            });
        }
    }
}
