package app.blockyard.ui.preview;

import app.blockyard.ui.core.Canvas;
import app.blockyard.ui.core.Fonts;
import app.blockyard.ui.core.Kit;
import app.blockyard.ui.core.Widgets;

import javax.imageio.ImageIO;
import java.io.File;

/** Vanilla screens (create world, statistics) as the restyle draws them, at GUI scale 2 on 1080p. */
final class VanillaPreviews {
    static final int A = PreviewMain.ACCENT;

    static void render(String out) throws Exception {
        createWorld(out);
        stats(out);
    }

    /** Centered vanilla text: our font, centered like drawCenteredTextWithShadow does with our widths. */
    static void center(Canvas c, String s, float cx, float y, int color) {
        Widgets.text(c, s, Widgets.TEXT, Math.round(cx - Fonts.width(s, Widgets.TEXT) / 2), y + 7, color);
    }

    static void title(Canvas c, String s, float cx, float y) {
        Widgets.text(c, s, Widgets.TITLE, Math.round(cx - Fonts.width(s, Widgets.TITLE) / 2), y + 8, Kit.WHITE);
    }

    static void createWorld(String out) throws Exception {
        AwtCanvas b = new AwtCanvas(1920, 1080);
        b.space(A, 3);
        AwtCanvas c = new AwtCanvas(1920, 1080, 2);
        c.g.drawImage(b.out, 0, 0, null);
        float W = c.width(), cx = W / 2;
        // Tab bar
        String[] tabs = {"Игра", "Мир", "Ещё"};
        float tw = 130, tx = cx - tw * 1.5f;
        for (int i = 0; i < 3; i++) Widgets.tab(c, tx + i * tw, 0, tw, 24, tabs[i], i == 0, i == 1, true);
        Widgets.separator(c, 0, 22, W, 2);
        float x = cx - 104, y = 48;
        Widgets.text(c, "Название мира", Widgets.TEXT, x, y + 7, 0xFFA0A0A0);
        y += 12;
        Widgets.textField(c, x, y, 208, 20, true);
        Widgets.text(c, "Новый мир", Widgets.TEXT, x + 4, y + 6 + 7, 0xFFE0E0E0);
        c.fillRound(x + 4 + Fonts.width("Новый мир", Widgets.TEXT) + 1, y + 5, 1, 10, 0, 0xFFD0D0D0);
        y += 22;
        Widgets.text(c, "Сохранено в: Новый мир", Widgets.TEXT, x, y + 7, 0xFFA0A0A0);
        y += 18;
        Widgets.button(c, x, y, 208, 20, "Режим игры: Выживание", true, true, A);
        y += 24;
        center(c, "Исследуйте загадочный мир, стройте, собирайте", cx, y, 0xFFA0A0A0);
        center(c, "ресурсы, создавайте предметы и сражайтесь с монстрами.", cx, y + 10, 0xFFA0A0A0);
        y += 26;
        Widgets.button(c, x, y, 208, 20, "Сложность: Нормальная", true, false, A);
        y += 24;
        Widgets.button(c, x, y, 208, 20, "Читы: Выкл.", true, false, A);
        y += 24;
        // A slider and a check box, as in other screens.
        Widgets.sliderTrack(c, x, y, 208, 20, false);
        Widgets.sliderFill(c, x, y, 20, x + 4 + 0.62f * 200, false, A);
        center(c, "Дальность прорисовки: 12 чанков", cx, y + 6, Kit.WHITE);
        y += 26;
        Widgets.checkbox(c, x, y, 17, true, false, A);
        Widgets.text(c, "Создавать структуры", Widgets.TEXT, x + 21, y + 5 + 7, 0xFFE0E0E0);
        // Footer
        float fy = 540 - 33;
        Widgets.separator(c, 0, fy - 2, W, 2);
        Widgets.button(c, cx - 154, fy + 6, 150, 20, "Создать новый мир", true, false, A);
        Widgets.button(c, cx + 4, fy + 6, 150, 20, "Отмена", true, false, A);
        ImageIO.write(c.out, "png", new File(out, "vanilla-create-world.png"));
    }

    static void stats(String out) throws Exception {
        AwtCanvas game = new AwtCanvas(1920, 1080);
        game.g.drawImage(Previews.blurredGame(), 0, 0, null);
        game.fillScreen(0x99060510);
        AwtCanvas c = new AwtCanvas(1920, 1080, 2);
        c.g.drawImage(game.out, 0, 0, null);
        float W = c.width(), cx = W / 2;
        title(c, "Статистика", cx, 15);
        Widgets.separator(c, 0, 31, W, 2);
        Widgets.listBand(c, 0, 33, W, 540 - 33 - 64);
        String[][] rows = {{"Время в игре", "4,2 ч"}, {"Пройдено пешком", "12,8 км"}, {"Прыжков", "3 418"}, {"Добыто блоков", "8 902"},
                {"Убито мобов", "214"}, {"Смертей", "7"}, {"Открыто сундуков", "96"}, {"Использован верстак", "143"}, {"Поймано рыб", "18"}};
        float y = 40;
        for (int i = 0; i < rows.length; i++) {
            float rx = cx - 110;
            if (i == 2) Widgets.selection(c, rx, y - 2, rx + 220, y + 12, A);
            int col = i % 2 == 0 ? 0xFFFFFFFF : 0xFF909090;
            Widgets.text(c, rows[i][0], Widgets.TEXT, rx + 2, y + 1 + 7, col);
            Widgets.text(c, rows[i][1], Widgets.TEXT, rx + 218 - Fonts.width(rows[i][1], Widgets.TEXT), y + 1 + 7, col);
            y += 14;
        }
        Widgets.scroller(c, cx + 124, 33, 6, 540 - 97, true);
        Widgets.scroller(c, cx + 124, 40, 6, 120, false);
        Widgets.separator(c, 0, 540 - 64, W, 2);
        float by = 540 - 56;
        Widgets.button(c, cx - 120, by, 80, 20, "Общая", false, false, A);
        Widgets.button(c, cx - 40, by, 80, 20, "Предметы", true, true, A);
        Widgets.button(c, cx + 40, by, 80, 20, "Существа", true, false, A);
        Widgets.button(c, cx - 100, by + 26, 200, 20, "Готово", true, false, A);
        ImageIO.write(c.out, "png", new File(out, "vanilla-stats.png"));
    }
}
