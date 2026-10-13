package app.blockyard.ui.preview;

import app.blockyard.ui.core.DialogView;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.LoadingView;
import app.blockyard.ui.core.PauseView;
import app.blockyard.ui.core.Raster;
import app.blockyard.ui.core.SettingsView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/** Previews of the settings, pause, loading and dialog screens. */
final class Previews {
    static double fov = 0.58, dist = 0.45, fps = 0.7, gamma = 0.5;
    static boolean full = false, vsync = true, ao = true, shadows = true;
    static int graphics = 1, clouds = 2, particles = 2;

    static void rest(String out) throws Exception {
        SettingsView.Model sm = settings(false);
        AwtCanvas c = new AwtCanvas(1920, 1080);
        SettingsView.draw(new Frame(c, -100, -100, 3f, PreviewMain.ACCENT, false), sm, new SettingsView.State());
        ImageIO.write(c.out, "png", new File(out, "settings.png"));

        BufferedImage game = blurredGame();
        AwtCanvas p = new AwtCanvas(1920, 1080);
        p.g.drawImage(game, 0, 0, null);
        p.fillScreen(0x73060510);
        PauseView.draw(new Frame(p, -100, -100, 3f, PreviewMain.ACCENT, false), pause("BY1-eyJhZGRyIjoiaXJvaDovL2JjNz"), 1);
        ImageIO.write(p.out, "png", new File(out, "pause.png"));

        AwtCanvas l = new AwtCanvas(1920, 1080);
        LoadingView.draw(new Frame(l, -100, -100, 3f, PreviewMain.ACCENT, false), "Выживание", "Загружаем мир…",
                PreviewMain.pic(0x78B9FF, 0x6FBF4A, 0x8A5A33), "w1", -1);
        LoadingView.button(new Frame(l, -100, -100, 3f, PreviewMain.ACCENT, false), "Отмена", l.width() / 2 - 15, 170, 30, 11);
        ImageIO.write(l.out, "png", new File(out, "loading.png"));

        BufferedImage home = ImageIO.read(new File(out, "home.png"));
        BufferedImage blurred = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        blurred.getGraphics().drawImage(home, 0, 0, null);
        Raster.boxBlur(blurred, 10, 3);
        AwtCanvas d = new AwtCanvas(1920, 1080);
        d.g.drawImage(blurred, 0, 0, null);
        d.fillScreen(0x59060510);
        DialogView.draw(new Frame(d, -100, -100, 3f, PreviewMain.ACCENT, false), dialog(), 1);
        ImageIO.write(d.out, "png", new File(out, "dialog.png"));

        AwtCanvas sg = new AwtCanvas(1920, 1080);
        sg.g.drawImage(game, 0, 0, null);
        sg.fillScreen(0x99060510);
        SettingsView.draw(new Frame(sg, -100, -100, 3f, PreviewMain.ACCENT, false), settings(true), new SettingsView.State());
        ImageIO.write(sg.out, "png", new File(out, "settings-ingame.png"));
    }

    static BufferedImage blurredGame() {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 1080; y++) {
            for (int x = 0; x < 1920; x++) {
                int c = y < 410 ? 0x8CC8FF : y < 500 ? 0x79B24F : 0x6A4A2E;
                if (((x / 120) + (y / 120)) % 2 == 0) c = PreviewMain.darker(PreviewMain.darker(c));
                img.setRGB(x, y, 0xFF000000 | c);
            }
        }
        Raster.boxBlur(img, 14, 3);
        return img;
    }

    static SettingsView.Model settings(boolean inGame) {
        List<SettingsView.Section> sections = List.of(
                new SettingsView.Section("Основное", "user", "Изменения применяются сразу", List.of(), false),
                new SettingsView.Section("Графика", "monitor", "Изменения применяются сразу", List.of(
                        new SettingsView.Group("Экран", List.of(
                                new SettingsView.Slider("Дальность прорисовки", null, () -> dist, v -> dist = v, () -> "12 чанков"),
                                new SettingsView.Slider("Макс. частота кадров", null, () -> fps, v -> fps = v, () -> "144 FPS"),
                                new SettingsView.Slider("Поле зрения", null, () -> fov, v -> fov = v, () -> "95"),
                                new SettingsView.Toggle("Полный экран", null, () -> full, v -> full = v),
                                new SettingsView.Toggle("Вертикальная синхронизация", null, () -> vsync, v -> vsync = v))),
                        new SettingsView.Group("Качество", List.of(
                                new SettingsView.Choice("Графика", null, new String[]{"Быстрая", "Детальная", "Лучшая"}, () -> graphics, i -> graphics = i),
                                new SettingsView.Choice("Облака", null, new String[]{"Выкл.", "Быстрые", "Детальные"}, () -> clouds, i -> clouds = i),
                                new SettingsView.Toggle("Мягкое освещение", null, () -> ao, v -> ao = v),
                                new SettingsView.Slider("Яркость", "Как у Mojang: «Мрачно» — «Ярко»", () -> gamma, v -> gamma = v, () -> "50%"),
                                new SettingsView.Choice("Размер интерфейса", null, new String[]{"Авто", "1", "2", "3", "4"}, () -> 2, i -> {}),
                                new SettingsView.Button("Расширенные настройки графики", "Sodium и другие моды графики", null, () -> {})))), false),
                new SettingsView.Section("Музыка и звуки", "sound", "", List.of(), false),
                new SettingsView.Section("Управление", "keyboard", "", List.of(), false),
                new SettingsView.Section("Чат", "chat", "", List.of(), false),
                new SettingsView.Section("Скин", "shirt", "", List.of(), false),
                new SettingsView.Section("Язык", "language", "", List.of(), false),
                new SettingsView.Section("Доступность", "eye", "", List.of(), false),
                new SettingsView.Section("Ресурспаки", "layers", "", List.of(), false));
        return new SettingsView.Model() {
            public List<SettingsView.Section> sections() { return sections; }
            public int section() { return 1; }
            public void section(int i) {}
            public boolean inGame() { return inGame; }
            public void done() {}
            public void openTab(int tab) {}
            public String user() { return "Fronya"; }
        };
    }

    static PauseView.Model pause(String invite) {
        return new PauseView.Model() {
            public String subtitle() { return "Выживание · день 148"; }
            public boolean singleplayer() { return true; }
            public String invite() { return invite; }
            public boolean copiedRecently() { return false; }
            public void resume() {}
            public void advancements() {}
            public void stats() {}
            public void settings() {}
            public void openToFriend() {}
            public void copy() {}
            public void quit() {}
        };
    }

    static DialogView.Model dialog() {
        return new DialogView.Model() {
            public String title() { return "Изменение сервера"; }
            public String name() { return "FunTime"; }
            public String address() { return "funtime.su"; }
            public int pack() { return 2; }
            public void pack(int i) {}
            public int focus() { return 1; }
            public void focus(int i) {}
            public boolean canSave() { return true; }
            public void save() {}
            public void close() {}
        };
    }
}
