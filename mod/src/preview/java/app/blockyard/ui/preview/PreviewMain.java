package app.blockyard.ui.preview;

import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Renders the views with fake data into PNGs, to compare with the mockups without the game. */
public final class PreviewMain {
    public static final int ACCENT = 0x8B5CFF;

    public static BufferedImage pic(int sky, int ground, int dirt) {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int c = y < 29 ? sky : y < 34 ? ground : dirt;
                if (((x / 4) + (y / 4)) % 2 == 0) c = darker(c);
                img.setRGB(x, y, 0xFF000000 | c);
            }
        }
        return img;
    }

    public static int darker(int c) {
        return (((c >> 16) & 255) * 15 / 16 << 16) | (((c >> 8) & 255) * 15 / 16 << 8) | ((c & 255) * 15 / 16);
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : ".";
        List<HomeView.Entry> worlds = List.of(
                new HomeView.Entry("w1", "Выживание", "Выживание · Сложность: нормальная · Minecraft 1.21.1", List.of("Играли сегодня", "День 148", "2,4 ГБ"), 0x9AA0B0, null, pic(0x78B9FF, 0x6FBF4A, 0x8A5A33)),
                new HomeView.Entry("w2", "Ночной город", "Творческий", List.of("Играли вчера"), 0x9AA0B0, null, pic(0x1B2547, 0x2D5B2A, 0x4A3220)),
                new HomeView.Entry("w3", "Пустыня", "Выживание", List.of("Играли 3 октября"), 0x9AA0B0, null, pic(0xFFD38A, 0xE8C77A, 0xE8C77A)),
                new HomeView.Entry("w4", "Незер", "Хардкор", List.of("Ещё не запускался"), 0x9AA0B0, null, pic(0x4A0D0D, 0xA12B1A, 0x3A0A0A)),
                new HomeView.Entry("w5", "Океан", "Выживание", List.of("Играли 1 октября"), 0x9AA0B0, null, pic(0x8FD0FF, 0x1E6FB3, 0x1E6FB3)));
        List<HomeView.Mod> mods = List.of(
                new HomeView.Mod("blockyard-ui", "Blockyard UI", "2.2.0", "Меню в стиле Blockyard", null),
                new HomeView.Mod("fabric-api", "Fabric API", "0.116.6", "Core API", null),
                new HomeView.Mod("iris", "Iris Shaders", "1.8.8", "Shaders", null),
                new HomeView.Mod("sodium", "Sodium", "0.6.13", "Rendering", null));
        List<HomeView.Shot> shots = new ArrayList<>();
        int[][] sc = {{0x78B9FF, 0x6FBF4A, 0x8A5A33}, {0x1B2547, 0x2D5B2A, 0x4A3220}, {0xC8DCFF, 0xF4F7FF, 0xF4F7FF},
                {0xFFD38A, 0xE8C77A, 0xE8C77A}, {0x8FD0FF, 0x1E6FB3, 0x1E6FB3}, {0x4A0D0D, 0xA12B1A, 0x3A0A0A}};
        for (int i = 0; i < 6; i++) shots.add(new HomeView.Shot("s" + i, pic(sc[i][0], sc[i][1], sc[i][2])));

        HomeView.Model model = new HomeView.Model() {
            public int tab() { return 0; }
            public void openTab(int tab) {}
            public List<HomeView.Entry> entries() { return worlds; }
            public int selected() { return 0; }
            public void select(int i) {}
            public void play(int i) {}
            public void add() {}
            public void settings() {}
            public void friend() {}
            public String user() { return "Fronya"; }
            public List<HomeView.Mod> mods() { return mods; }
            public List<HomeView.Shot> shots() { return shots; }
            public int shotCount() { return 36; }
            public void openShots() {}
            public void playAndInvite() {}
            public List<HomeView.MenuItem> menu() { return List.of(); }
            public boolean loading() { return false; }
        };

        AwtCanvas c = new AwtCanvas(1920, 1080);
        HomeView.State st = new HomeView.State();
        st.changedAt = 0;
        HomeView.draw(new Frame(c, -100, -100, 3f, ACCENT, false), model, st);
        ImageIO.write(c.out, "png", new File(out, "home.png"));
        Previews.rest(out);
    }
}
