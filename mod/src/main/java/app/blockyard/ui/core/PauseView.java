package app.blockyard.ui.core;

/** The pause menu, like mockup 3: a glass panel over the blurred game. */
public final class PauseView {
    public interface Model {
        String subtitle();

        boolean singleplayer();

        /** null when not hosting; "" while the code is being created. */
        String invite();

        boolean copiedRecently();

        void resume();

        void advancements();

        void stats();

        void settings();

        void openToFriend();

        void copy();

        void quit();
    }

    private PauseView() {}

    public static void draw(Frame f, Model m, float appear) {
        Canvas c = f.c;
        float w = 140, pad = 8.5f;
        float bh = Kit.wideBtnH(0), gap = 2.5f;
        String invite = m.invite();
        boolean code = invite != null && !invite.isEmpty();
        float inviteH = code ? 3.5f * 2 + Kit.lh(Kit.SUB) + 1 + Kit.lh(Kit.BODY_B) : 0;
        float h = pad * 2 + Kit.lh(Kit.H2_PAUSE) + 1 + Kit.lh(Kit.BODY) + 6 + (bh + gap) * 4 - gap + (code ? inviteH + gap : 0);
        float x = (f.vw - w) / 2, y = (270 - h) / 2 + (1 - appear) * 5;
        c.shadow(x, y + 7.5f, w, h, 7.5f, 22, 0.6f * appear);
        c.fillRound(x, y, w, h, 7.5f, Kit.a(0.9f * appear, 0x2A2838), Kit.a(0.92f * appear, 0x1C1A28), 0.25f, Kit.a(0.09f * appear, 0xFFFFFF));
        float ix = x + pad, iw = w - pad * 2, iy = y + pad;
        c.text("Пауза", Kit.H2_PAUSE, ix, iy, Kit.WHITE);
        iy += Kit.lh(Kit.H2_PAUSE) + 1;
        c.text(Fonts.fit(m.subtitle(), Kit.BODY, iw), Kit.BODY, ix, iy, Kit.MUTED);
        iy += Kit.lh(Kit.BODY) + 6;
        Kit.wideBtn(f, "Вернуться к игре", ix, iy, iw, 1, m::resume);
        iy += bh + gap;
        float half = (iw - gap) / 2;
        Kit.wideBtn(f, "Достижения", ix, iy, half, 0, m::advancements);
        Kit.wideBtn(f, "Статистика", ix + half + gap, iy, half, 0, m::stats);
        iy += bh + gap;
        if (m.singleplayer()) {
            Kit.wideBtn(f, "Настройки", ix, iy, half, 0, m::settings);
            String label = invite == null ? "Пригласить друга" : code ? "Мир открыт" : "Создаём код…";
            Kit.wideBtn(f, label, ix + half + gap, iy, half, 0, m::openToFriend);
        } else {
            Kit.wideBtn(f, "Настройки", ix, iy, iw, 0, m::settings);
        }
        iy += bh + gap;
        if (code) {
            c.fillRound(ix, iy, iw, inviteH, 4, Kit.a(0.07f, 0xFFFFFF), Kit.a(0.07f, 0xFFFFFF), 0.25f, Kit.a(0.1f, 0xFFFFFF));
            c.text("Код для друга — вставить в Blockyard → «Подключиться к другу»", Kit.SUB, ix + 4, iy + 3.5f, Kit.MUTED);
            float codeY = iy + 3.5f + Kit.lh(Kit.SUB) + 1;
            String label = m.copiedRecently() ? "Скопировано" : "Копировать";
            float bw = Kit.w(label, Kit.TAG) + 7, bH = Kit.lh(Kit.TAG) + 2;
            c.text(Fonts.fit(invite, Kit.BODY_B, iw - bw - 12), Kit.BODY_B, ix + 4, codeY, Kit.WHITE);
            float bx = ix + iw - 4 - bw, by = codeY + Kit.lh(Kit.BODY_B) / 2 - bH / 2;
            boolean hov = f.hover(bx, by, bw, bH);
            c.fillRound(bx, by, bw, bH, bH / 2, m.copiedRecently() ? (0xFF000000 | Kit.GREEN) : hov ? Kit.WHITE : 0xFFEDEBF5);
            Kit.textCenter(c, label, Kit.TAG, bx + bw / 2, by + bH / 2, Kit.INK);
            f.hit(bx, by, bw, bH, m::copy);
            iy += inviteH + gap;
        }
        Kit.wideBtn(f, m.singleplayer() ? "Сохранить и выйти в меню" : "Отключиться", ix, iy, iw, 2, m::quit);
        Kit.textCenter(c, "Esc — продолжить", Kit.BODY, f.vw / 2, 270 - 7.5f - Kit.lh(Kit.BODY) / 2, Kit.a(0.4f, 0xFFFFFF));
    }
}
