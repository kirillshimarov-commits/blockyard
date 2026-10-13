package app.blockyard.ui.core;

/** The server add/edit dialog, like mockup 5. */
public final class DialogView {
    public interface Model {
        String title();

        String name();

        String address();

        /** 0 enabled, 1 disabled, 2 prompt. */
        int pack();

        void pack(int i);

        int focus();

        void focus(int i);

        boolean canSave();

        void save();

        void close();
    }

    private DialogView() {}

    public static void draw(Frame f, Model m, float appear) {
        Canvas c = f.c;
        float w = 155, pad = 7.5f;
        float labelH = Kit.lh(Kit.TAG) + 2, inputH = 4 * 2 + Kit.lh(Kit.PILL);
        float segH = 2.25f * 2 + Kit.lh(Kit.SEG) + 2;
        float h = pad * 2 + Kit.lh(Kit.H2_MODAL) + 5.5f + (labelH + inputH + 3.5f) * 2 + labelH + segH + 5.5f + Kit.wideBtnH(1);
        float x = (f.vw - w) / 2, y = (270 - h) / 2 + (1 - appear) * 5;
        c.shadow(x, y + 7.5f, w, h, 7.5f, 22, 0.6f * appear);
        c.fillRound(x, y, w, h, 7.5f, Kit.a(0.94f * appear, 0x2A2838), Kit.a(0.95f * appear, 0x1C1A28), 0.25f, Kit.a(0.09f * appear, 0xFFFFFF));
        float ix = x + pad, iw = w - pad * 2, iy = y + pad;
        Kit.textMid(c, m.title(), Kit.H2_MODAL, ix, iy + Kit.lh(Kit.H2_MODAL) / 2, Kit.WHITE);
        float xs = 8.5f, xx = ix + iw - xs, xy = iy + Kit.lh(Kit.H2_MODAL) / 2 - xs / 2;
        boolean hovX = f.hover(xx, xy, xs, xs);
        c.disk(xx + xs / 2, xy + xs / 2, xs / 2, hovX ? 0xFFFF7A70 : 0xFFF0645A);
        c.icon("close", xx + 1.75f, xy + 1.75f, 5, 0xFF3A0E0E);
        f.hit(xx, xy, xs, xs, m::close);
        iy += Kit.lh(Kit.H2_MODAL) + 5.5f;
        iy = field(f, m, "Название", m.name(), "Сервер Minecraft", 0, ix, iy, iw, labelH, inputH);
        iy = field(f, m, "IP-адрес", m.address(), "например, play.example.ru", 1, ix, iy, iw, labelH, inputH);
        c.text("Ресурспак сервера", Kit.TAG, ix + 1, iy, Kit.MUTED);
        iy += labelH;
        Kit.segmented(f, new String[]{"Включён", "Выключен", "Спрашивать"}, m.pack(), ix, iy + segH / 2, m::pack);
        iy += segH + 5.5f;
        if (m.canSave()) Kit.wideBtn(f, "Сохранить", ix, iy, iw, 1, m::save);
        else {
            float bh = Kit.wideBtnH(1);
            c.fillRound(ix, iy, iw, bh, bh / 2, Kit.a(0.35f, 0xFFFFFF));
            Kit.textCenter(c, "Сохранить", Kit.BTN_B, ix + iw / 2, iy + bh / 2, Kit.a(0.7f, 0x0B0A12));
        }
    }

    private static float field(Frame f, Model m, String label, String value, String placeholder, int which, float x, float y, float w, float labelH, float h) {
        Canvas c = f.c;
        c.text(label, Kit.TAG, x + 1, y, Kit.MUTED);
        y += labelH;
        boolean focused = m.focus() == which;
        c.fillRound(x, y, w, h, 4, Kit.a(focused ? 0.1f : 0.08f, 0xFFFFFF), Kit.a(focused ? 0.1f : 0.08f, 0xFFFFFF), focused ? 0.5f : 0, Kit.a(0.5f, 0xFFFFFF));
        boolean empty = value.isEmpty();
        String shown = Fonts.fit(empty ? placeholder : value, Kit.PILL, w - 12);
        Kit.textMid(c, shown, Kit.PILL, x + 5, y + h / 2, empty ? Kit.a(0.35f, 0xFFFFFF) : Kit.WHITE);
        if (focused && ((long) (f.t * 2)) % 2 == 0) {
            float cx = x + 5 + (empty ? 0 : Kit.w(shown, Kit.PILL)) + 0.4f;
            c.fillRound(cx, y + h / 2 - 2.6f, 0.5f, 5.2f, 0.25f, Kit.WHITE);
        }
        f.hit(x, y, w, h, () -> m.focus(which));
        return y + h + 3.5f;
    }
}
