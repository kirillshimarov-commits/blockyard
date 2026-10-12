package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The main menu, laid out like the launcher's home: tabs, tiles, a large card and a shelf of cards. */
public class HomeScreen extends BlockyardScreen {
    static final int TAB_SINGLE = 0, TAB_MULTI = 1, TAB_PACKS = 2, TAB_MODS = 3;

    // Remembered while the game runs, so coming back keeps the place.
    private static int tab = TAB_SINGLE;
    private static final int[] selected = {0, 0};

    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    ServerList servers;
    private List<LevelSummary> worlds = null;
    private List<ModContainer> mods = List.of();
    private List<Path> screenshots = List.of();
    private int screenshotCount = 0;

    private boolean menuOpen = false;
    private float menuX, menuY;
    private long changedAt = System.currentTimeMillis();
    private float modsScroll = 0;
    private String flash = null;
    private long flashAt = 0;

    public HomeScreen() {
        super(Text.literal("Blockyard"));
    }

    public HomeScreen(int tab) {
        this();
        if (tab == TAB_SINGLE || tab == TAB_MULTI || tab == TAB_MODS) HomeScreen.tab = tab;
    }

    @Override
    protected void init() {
        super.init();
        servers = new ServerList(client);
        servers.loadFile();
        for (int i = 0; i < servers.size(); i++) ping(servers.get(i));
        loadWorlds();
        mods = FabricLoader.getInstance().getAllMods().stream()
                .filter(m -> !m.getMetadata().getType().equals("builtin") && m.getContainingMod().isEmpty())
                .sorted(Comparator.comparing(m -> m.getMetadata().getName().toLowerCase(Locale.ROOT)))
                .toList();
        loadScreenshots();
    }

    private void loadWorlds() {
        try {
            LevelStorage storage = client.getLevelStorage();
            storage.loadSummaries(storage.getLevelList()).thenAcceptAsync(list -> {
                List<LevelSummary> sorted = new ArrayList<>(list);
                sorted.sort(Comparator.comparingLong(LevelSummary::getLastPlayed).reversed());
                worlds = sorted;
            }, client);
        } catch (Exception e) {
            worlds = List.of();
        }
    }

    private void loadScreenshots() {
        Path dir = client.runDirectory.toPath().resolve("screenshots");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.filter(p -> p.toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            screenshotCount = all.size();
            screenshots = all.subList(0, Math.min(6, all.size()));
        } catch (Exception ignored) {
            // No screenshots card then.
        }
    }

    void ping(ServerInfo info) {
        if (info.getStatus() != ServerInfo.Status.INITIAL) return;
        info.setStatus(ServerInfo.Status.PINGING);
        info.label = Text.empty();
        info.playerCountLabel = Text.empty();
        Images.WORKER.submit(() -> {
            try {
                pinger.add(info, () -> client.execute(servers::saveFile), () -> info.setStatus(ServerInfo.Status.SUCCESSFUL));
            } catch (Exception e) {
                info.setStatus(ServerInfo.Status.UNREACHABLE);
            }
        });
    }

    @Override
    public void tick() {
        pinger.tick();
    }

    @Override
    public void removed() {
        pinger.cancel();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ================================================================ entries

    private int count() {
        return tab == TAB_MULTI ? servers.size() : (worlds == null ? 0 : worlds.size());
    }

    private int current() {
        int n = count();
        if (n == 0) return -1;
        int t = tab == TAB_MULTI ? 1 : 0;
        selected[t] = Math.max(0, Math.min(selected[t], n - 1));
        return selected[t];
    }

    private int sel() {
        return tab == TAB_MULTI ? 1 : 0;
    }

    private String nameOf(int i) {
        if (tab == TAB_MULTI) return servers.get(i).name;
        LevelSummary w = worlds.get(i);
        return w.getDisplayName().isEmpty() ? w.getName() : w.getDisplayName();
    }

    private Identifier iconOf(int i) {
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(i);
            return Images.fromBytes("s" + s.address, s.getFavicon());
        }
        LevelSummary w = worlds.get(i);
        return Images.fromFile("w" + w.getName(), w.getIconPath());
    }

    void play(int i) {
        click();
        BlockyardUi.loadingTitle = nameOf(i);
        BlockyardUi.loadingIcon = iconOf(i);
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(i);
            ConnectScreen.connect(this, client, ServerAddress.parse(s.address), s, false, null);
        } else {
            client.createIntegratedServerLoader().start(worlds.get(i).getName(), () -> client.setScreen(new HomeScreen()));
        }
    }

    private void add() {
        click();
        if (tab == TAB_MULTI) client.setScreen(new ServerEditScreen(this, -1));
        else CreateWorldScreen.create(client, this);
    }

    private void select(int i) {
        if (selected[sel()] == i) return;
        selected[sel()] = i;
        changedAt = System.currentTimeMillis();
        click();
    }

    private void openTab(int target) {
        menuOpen = false;
        modal = false;
        if (target == TAB_PACKS) {
            client.setScreen(new SettingsScreen(this, SettingsScreen.PACKS));
            return;
        }
        if (tab == target) return;
        tab = target;
        modsScroll = 0;
        changedAt = System.currentTimeMillis();
    }

    private void notice(String text) {
        flash = text;
        flashAt = System.currentTimeMillis();
    }

    // ================================================================ rendering

    @Override
    protected void draw(DrawContext ctx, float delta) {
        int accent = BlockyardUi.accent;
        float a = ease((System.currentTimeMillis() - changedAt) / 380f);
        drawTopBar(ctx, tab, false, 1, this::openTab);
        if (tab == TAB_MODS) {
            drawModsTab(ctx, accent);
        } else {
            int idx = current();
            drawTiles(ctx, idx, accent);
            if (idx >= 0) drawHero(ctx, idx, accent, a);
            else drawEmpty(ctx, accent);
            drawShelf(ctx, accent);
        }
        if (menuOpen) drawMenu(ctx);
        long now = System.currentTimeMillis();
        if (flash != null && now - flashAt < 2600) {
            float fa = Math.min(1, (2600 - (now - flashAt)) / 400f);
            float w = Ui.width(textRenderer, flash, Ui.BODY) + 16;
            Ui.round(ctx, (vw - w) / 2, CANVAS_H - 22, w, 11, 5.5f, Ui.argb(0.92f * fa, 0x1A1828));
            Ui.centered(ctx, textRenderer, flash, Ui.BODY, vw / 2, CANVAS_H - 19, Ui.argb(fa, WHITE));
        }
    }

    // ---------------------------------------------------------------- tiles

    private void drawTiles(DrawContext ctx, int idx, int accent) {
        float action = 16, base = 21, big = 30, gap = 4, left = 10, center = 45;
        int n = count();
        float selX = left + action + gap + (base + gap) * Math.max(0, idx);
        float scroll = Math.max(0, selX + big + 30 - vw);
        float x = left - scroll;

        // First tile: which kind of list this is.
        Ui.round(ctx, x, center - action / 2, action, action, 4.5f, 0x14FFFFFF);
        Ui.icon(ctx, tab == TAB_MULTI ? "globe" : "cube", x + 4, center - 4, 8, 0xE6FFFFFF);
        x += action + gap;

        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            boolean s = i == idx;
            float size = s ? big : base;
            float ty = center - size / 2;
            boolean hov = hover(x, ty, size, size);
            if (hov && !s) ty -= 1;
            float r = s ? 6.5f : 5;
            if (s) {
                float pulse = 0.85f + 0.15f * (float) Math.sin(now / 600.0);
                Ui.light(ctx, Ui.tex("glow"), x - size * 0.6f, ty - size * 0.45f, size * 2.2f, size * 2.2f, Ui.argb(0.4f * pulse, accent));
                Ui.shadow(ctx, x, ty + 3, size, size, 7, 0.55f);
                Ui.round(ctx, x - 2.25f, ty - 2.25f, size + 4.5f, size + 4.5f, r + 2.25f, 0x1FFFFFFF);
                Ui.round(ctx, x - 1, ty - 1, size + 2, size + 2, r + 1, 0xFFFFFFFF);
            }
            drawArt(ctx, i, x, ty, size, r, accent, s || hov ? 1 : 0.78f);
            final int target = i;
            region(x, ty, size, size, () -> {
                if (selected[sel()] == target) play(target);
                else select(target);
            });
            x += size + gap;
        }
        // "+" tile at the end.
        boolean hovAdd = hover(x, center - action / 2, action, action);
        Ui.round(ctx, x, center - action / 2, action, action, 4.5f, hovAdd ? 0x24FFFFFF : 0x0AFFFFFF);
        Ui.icon(ctx, "plus", x + 4.5f, center - 3.5f, 7, hovAdd ? 0xFFFFFFFF : 0xB0FFFFFF);
        region(x, center - action / 2, action, action, this::add);

        if (idx >= 0) {
            float cx = left + action + gap - scroll;
            float cy = 65.5f;
            String tag = tab == TAB_MULTI ? "сервер" : "мир";
            float tw = Ui.width(textRenderer, tag, Ui.BOLD) + 5;
            Ui.round(ctx, cx, cy, tw, 6.5f, 3.25f, 0xFFFFFFFF);
            Ui.text(ctx, textRenderer, tag, Ui.SMALL, cx + 2.6f, cy + 1.6f, Ui.argb(1, INK));
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, nameOf(idx), Ui.BODY, vw * 0.4f), Ui.BODY, cx + tw + 2.5f, cy + 1.1f, 0xFFFFFFFF);
        }
    }

    /** The picture of an entry: its icon, or its first letter on an accent gradient. */
    private void drawArt(DrawContext ctx, int i, float x, float y, float size, float r, int accent, float a) {
        Identifier icon = iconOf(i);
        if (icon != null) {
            Ui.round(ctx, x, y, size, size, r, Ui.argb(a, 0x16141F));
            float inset = size * 0.06f;
            Ui.image(ctx, icon, x + inset, y + inset, size - inset * 2, size - inset * 2, Ui.argb(a, WHITE));
        } else {
            Ui.round(ctx, x, y, size, size, r, Ui.argb(a, Ui.mix(accent, WHITE, 0.15f)), Ui.argb(a, Ui.mix(accent, 0x000000, 0.35f)));
            String name = nameOf(i);
            String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            MatrixStack m = ctx.getMatrices();
            m.push();
            float scale = size / 32f;
            m.translate(x + size / 2, y + size / 2 - 8.5f * scale, 0);
            m.scale(scale, scale, 1);
            Ui.centered(ctx, textRenderer, letter, Ui.TITLE, 0, 0, Ui.argb(a, WHITE));
            m.pop();
        }
    }

    // ---------------------------------------------------------------- hero

    private void drawHero(DrawContext ctx, int idx, int accent, float a) {
        float lift = (1 - a) * 6;
        float art = Math.min(80, vw * 0.2f);
        float artX = vw - art - 17.5f, artY = 75 - lift * 0.4f;
        float left = 17.5f, maxText = artX - left - 20;

        Ui.light(ctx, Ui.tex("glow"), artX - art * 0.55f, artY - art * 0.5f, art * 2.1f, art * 2.1f, Ui.argb(0.35f * a, accent));
        Ui.shadow(ctx, artX, artY + 6, art, art, 16, 0.7f * a);
        drawArt(ctx, idx, artX, artY, art, 8.5f, accent, a);

        float y = 81 + lift;
        Ui.text(ctx, textRenderer, Ui.fit(textRenderer, nameOf(idx), Ui.TITLE, maxText), Ui.TITLE, left, y, Ui.argb(a, WHITE));
        y += 22;

        String detail;
        List<String> chips = new ArrayList<>();
        int dot = 0x9AA0B0;
        String motd = null;
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(idx);
            detail = "IP: " + s.address;
            switch (s.getStatus()) {
                case SUCCESSFUL -> {
                    chips.add("Онлайн: " + s.playerCountLabel.getString());
                    chips.add(s.ping + " мс");
                    dot = 0x3EE08F;
                }
                case INCOMPATIBLE -> {
                    chips.add("Версия сервера: " + s.version.getString());
                    dot = 0xF2B84B;
                }
                case UNREACHABLE -> {
                    chips.add("Сервер недоступен");
                    dot = 0xEF5B5B;
                }
                default -> chips.add("Опрашиваем сервер…");
            }
            if (s.label != null && !s.label.getString().isBlank()) motd = s.label.getString().replace('\n', ' ').trim();
        } else {
            LevelSummary w = worlds.get(idx);
            detail = w.getDetails().getString();
            long last = w.getLastPlayed();
            if (last <= 0) chips.add("Ещё не запускался");
            else {
                LocalDate d = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate();
                LocalDate today = LocalDate.now();
                chips.add(d.equals(today) ? "Играли сегодня" : d.equals(today.minusDays(1)) ? "Играли вчера"
                        : "Играли " + d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))));
            }
            if (w.isHardcore()) chips.add("Хардкор");
            if (w.hasCheats()) chips.add("Читы");
        }
        Ui.text(ctx, textRenderer, Ui.fit(textRenderer, detail, Ui.BODY, maxText), Ui.BODY, left, y, Ui.argb(0.62f * a, WHITE));
        y += 7.5f;

        float cx = left;
        for (int c = 0; c < chips.size(); c++) {
            String chip = chips.get(c);
            boolean withDot = c == 0;
            float w = Ui.width(textRenderer, chip, Ui.BODY) + (withDot ? 12 : 8);
            Ui.round(ctx, cx, y, w, 9, 3, Ui.argb(0.08f * a, WHITE));
            float tx = cx + 4;
            if (withDot) {
                Ui.light(ctx, Ui.tex("glow"), cx + 2, y + 1.5f, 6, 6, Ui.argb(0.7f * a, dot));
                Ui.circle(ctx, cx + 5, y + 4.5f, 1.15f, Ui.argb(a, dot));
                tx = cx + 8;
            }
            Ui.text(ctx, textRenderer, chip, Ui.BODY, tx, y + 2.3f, Ui.argb(a, WHITE));
            cx += w + 2.5f;
        }
        if (motd != null) {
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, motd, Ui.BODY, maxText), Ui.BODY, left, y + 12, Ui.argb(0.45f * a, WHITE));
        }

        float by = 126 + lift, ph = 15.5f;
        String label = "Играть";
        float pw = Ui.width(textRenderer, label, Ui.BOLD) * 1.2f + 26;
        boolean hovPlay = hover(left, by, pw, ph);
        if (hovPlay) Ui.light(ctx, Ui.tex("glow"), left - 14, by - 12, pw + 28, ph + 24, Ui.argb(0.5f * a, accent));
        Ui.shadow(ctx, left, by + 2.5f, pw, ph, 7, 0.45f * a);
        float grow = hovPlay ? 0.8f : 0;
        Ui.round(ctx, left - grow, by - grow, pw + grow * 2, ph + grow * 2, ph / 2 + grow, Ui.argb(a, WHITE));
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(left + 10, by + 5.2f, 0);
        m.scale(1.2f, 1.2f, 1);
        Ui.text(ctx, textRenderer, label, Ui.BOLD, 0, 0, Ui.argb(a, INK));
        m.pop();
        Ui.icon(ctx, "play", left + pw - 12, by + 5.5f, 4.5f, Ui.argb(a, INK));
        region(left, by, pw, ph, () -> play(idx));

        float dx = left + pw + 3.5f, dr = ph / 2;
        boolean hovMore = hover(dx, by, ph, ph);
        Ui.circle(ctx, dx + dr, by + dr, dr, Ui.argb((hovMore ? 0.2f : 0.1f) * a, WHITE));
        for (int d = -1; d <= 1; d++) Ui.circle(ctx, dx + dr + d * 2.4f, by + dr, 0.75f, Ui.argb(a, WHITE));
        region(dx, by, ph, ph, () -> {
            click();
            menuOpen = true;
            modal = true;
            menuX = dx;
            menuY = by;
        });
    }

    private void drawEmpty(DrawContext ctx, int accent) {
        float cy = 100;
        boolean loading = tab == TAB_SINGLE && worlds == null;
        String title = tab == TAB_MULTI ? "Серверов пока нет" : loading ? "Загружаем миры…" : "Миров пока нет";
        Ui.text(ctx, textRenderer, title, Ui.HEAD, 17.5f, cy, 0xFFFFFFFF);
        if (loading) return;
        Ui.text(ctx, textRenderer, tab == TAB_MULTI ? "Добавьте сервер по IP-адресу" : "Создайте свой первый мир",
                Ui.BODY, 17.5f, cy + 11, Ui.argb(0.55f, WHITE));
        pillButton(ctx, tab == TAB_MULTI ? "Добавить сервер" : "Создать мир", 17.5f, cy + 22, 13, true, 1, this::add);
    }

    // ---------------------------------------------------------------- shelf

    private void drawShelf(DrawContext ctx, int accent) {
        float left = 17.5f, gap = 5, top = 176.5f, h = CANVAS_H - 12.5f - top;
        float w = (vw - left * 2 - gap * 2) / 3;
        for (int c = 0; c < 3; c++) {
            float x = left + c * (w + gap);
            panel(ctx, x, top, w, h, 6, 1);
            switch (c) {
                case 0 -> drawModsCard(ctx, x, top, w, h, accent);
                case 1 -> drawScreenshotsCard(ctx, x, top, w, h);
                default -> drawFriendCard(ctx, x, top, w, h);
            }
        }
    }

    private void cardHeader(DrawContext ctx, String title, String right, float x, float y, float w) {
        Ui.text(ctx, textRenderer, title, Ui.BOLD, x + 5.5f, y + 5.5f, 0xFFFFFFFF);
        if (right != null) {
            Ui.text(ctx, textRenderer, right, Ui.BODY, x + w - 5.5f - Ui.width(textRenderer, right, Ui.BODY), y + 5.5f, Ui.argb(0.6f, WHITE));
        }
    }

    private void cardLink(DrawContext ctx, String label, float x, float y, Runnable action) {
        float w = Ui.width(textRenderer, label, Ui.SMALL) + 4;
        boolean hov = hover(x, y - 1, w, 6);
        Ui.text(ctx, textRenderer, label, Ui.SMALL, x, y, Ui.argb(hov ? 1 : 0.6f, WHITE));
        region(x, y - 1, w, 6, () -> {
            click();
            action.run();
        });
    }

    private void drawModsCard(DrawContext ctx, float x, float y, float w, float h, int accent) {
        cardHeader(ctx, "Моды в сборке", String.valueOf(mods.size()), x, y, w);
        float ry = y + 15;
        int shown = Math.min(4, mods.size());
        for (int i = 0; i < shown; i++) {
            ModMetadata meta = mods.get(i).getMetadata();
            modIcon(ctx, mods.get(i), x + 5.5f, ry, 9.5f, accent);
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, meta.getName(), Ui.BODY, w - 45), Ui.BODY, x + 18.5f, ry + 2.8f, 0xFFFFFFFF);
            String v = Ui.fit(textRenderer, meta.getVersion().getFriendlyString(), Ui.SMALL, 24);
            Ui.text(ctx, textRenderer, v, Ui.SMALL, x + w - 5.5f - Ui.width(textRenderer, v, Ui.SMALL), ry + 3.3f, Ui.argb(0.55f, WHITE));
            ry += 12.5f;
        }
        if (mods.isEmpty()) Ui.text(ctx, textRenderer, "Модов нет", Ui.BODY, x + 5.5f, ry + 2, Ui.argb(0.55f, WHITE));
        cardLink(ctx, "Все моды ›", x + 5.5f, y + h - 8, () -> openTab(TAB_MODS));
    }

    private void modIcon(DrawContext ctx, ModContainer mod, float x, float y, float size, int accent) {
        Identifier icon = null;
        String path = mod.getMetadata().getIconPath(64).orElse(null);
        if (path != null) icon = Images.fromFile("m" + mod.getMetadata().getId(), mod.findPath(path).orElse(null));
        if (icon != null) {
            Ui.round(ctx, x, y, size, size, 2.6f, 0xFF1A1826);
            Ui.image(ctx, icon, x, y, size, size, 0xFFFFFFFF);
        } else {
            int c = Ui.mix(accent, (mod.getMetadata().getId().hashCode() & 0xFFFFFF) | 0x404040, 0.45f);
            Ui.round(ctx, x, y, size, size, 2.6f, Ui.argb(1, c));
            Ui.centered(ctx, textRenderer, mod.getMetadata().getName().substring(0, 1).toUpperCase(Locale.ROOT), Ui.BOLD, x + size / 2, y + size / 2 - 2.2f, 0xFFFFFFFF);
        }
    }

    private void drawScreenshotsCard(DrawContext ctx, float x, float y, float w, float h) {
        cardHeader(ctx, "Скриншоты", String.valueOf(screenshotCount), x, y, w);
        float gx = x + 5.5f, gy = y + 14, gap = 2;
        float tw = (w - 11 - gap * 2) / 3, th = tw * 10 / 16;
        if (screenshots.isEmpty()) {
            Ui.text(ctx, textRenderer, "Пока пусто: нажмите F2 в игре", Ui.BODY, gx, gy + 4, Ui.argb(0.55f, WHITE));
        }
        for (int i = 0; i < screenshots.size(); i++) {
            float tx = gx + (i % 3) * (tw + gap), ty = gy + (i / 3) * (th + gap);
            Path p = screenshots.get(i);
            Identifier thumb = Images.thumbnail("shot" + p, p, 192, 120);
            Ui.round(ctx, tx, ty, tw, th, 2.5f, 0x14FFFFFF);
            if (thumb != null) Ui.image(ctx, thumb, tx, ty, tw, th, 0xFFFFFFFF);
        }
        cardLink(ctx, "Открыть папку ›", x + 5.5f, y + h - 8, () -> {
            File dir = client.runDirectory.toPath().resolve("screenshots").toFile();
            dir.mkdirs();
            Util.getOperatingSystem().open(dir);
        });
    }

    private void drawFriendCard(DrawContext ctx, float x, float y, float w, float h) {
        cardHeader(ctx, "Игра с другом", "Blockyard", x, y, w);
        Ui.text(ctx, textRenderer, "Открой мир для друга", Ui.HEAD, x + 5.5f, y + 15, 0xFFFFFFFF);
        String[] lines = {"Зайди в мир, нажми Esc и «Пригласить друга».", "Код появится прямо в меню паузы — без Hamachi."};
        for (int i = 0; i < lines.length; i++) {
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, lines[i], Ui.SMALL, w - 11), Ui.SMALL, x + 5.5f, y + 26 + i * 5.5f, Ui.argb(0.6f, WHITE));
        }
        if (tab == TAB_SINGLE && current() >= 0) {
            pillButton(ctx, "Играть и пригласить", x + 5.5f, y + h - 16.5f, 11.5f, true, 1, () -> {
                BlockyardUi.inviteOnJoin = true;
                play(current());
            });
        }
    }

    // ---------------------------------------------------------------- mods tab

    private void drawModsTab(DrawContext ctx, int accent) {
        float left = 17.5f, top = 34, cols = vw > 380 ? 3 : 2, gap = 5;
        Ui.text(ctx, textRenderer, "Моды", Ui.TITLE, left, top, 0xFFFFFFFF);
        Ui.text(ctx, textRenderer, mods.size() + " установлено в этой сборке · добавляйте и удаляйте моды в лаунчере",
                Ui.BODY, left, top + 21, Ui.argb(0.6f, WHITE));
        float w = (vw - left * 2 - gap * (cols - 1)) / cols, h = 26;
        float listTop = top + 32, listH = CANVAS_H - listTop - 8;
        float maxScroll = Math.max(0, (float) Math.ceil(mods.size() / cols) * (h + gap) - listH);
        modsScroll = Math.max(0, Math.min(modsScroll, maxScroll));
        ctx.enableScissor((int) 0, (int) (listTop * k), width, (int) ((listTop + listH) * k));
        for (int i = 0; i < mods.size(); i++) {
            float x = left + (i % (int) cols) * (w + gap);
            float y = listTop + (i / (int) cols) * (h + gap) - modsScroll;
            if (y + h < listTop || y > listTop + listH) continue;
            ModMetadata meta = mods.get(i).getMetadata();
            Ui.round(ctx, x, y, w, h, 5, hover(x, y, w, h) ? 0x1AFFFFFF : 0x10FFFFFF);
            modIcon(ctx, mods.get(i), x + 4.5f, y + 4.5f, 17, accent);
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, meta.getName(), Ui.BOLD, w - 50), Ui.BOLD, x + 26, y + 5, 0xFFFFFFFF);
            String v = Ui.fit(textRenderer, meta.getVersion().getFriendlyString(), Ui.SMALL, 26);
            Ui.text(ctx, textRenderer, v, Ui.SMALL, x + w - 4.5f - Ui.width(textRenderer, v, Ui.SMALL), y + 5.6f, Ui.argb(0.5f, WHITE));
            String desc = meta.getDescription().replace('\n', ' ');
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, desc.isBlank() ? meta.getId() : desc, Ui.SMALL, w - 32), Ui.SMALL, x + 26, y + 14.5f, Ui.argb(0.55f, WHITE));
        }
        ctx.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == TAB_MODS) {
            modsScroll -= (float) verticalAmount * 18;
            return true;
        }
        if (verticalAmount != 0 && count() > 0) {
            int next = selected[sel()] + (verticalAmount < 0 ? 1 : -1);
            if (next >= 0 && next < count()) select(next);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---------------------------------------------------------------- menu

    private void drawMenu(DrawContext ctx) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int idx = current();
        if (tab == TAB_MULTI) {
            if (idx >= 0) {
                labels.add("Изменить сервер");
                actions.add(() -> client.setScreen(new ServerEditScreen(this, idx)));
                labels.add("Обновить статус");
                actions.add(() -> {
                    ServerInfo s = servers.get(idx);
                    s.setStatus(ServerInfo.Status.INITIAL);
                    ping(s);
                });
                labels.add("Удалить сервер");
                actions.add(() -> {
                    String name = servers.get(idx).name;
                    servers.remove(servers.get(idx));
                    servers.saveFile();
                    notice("Сервер «" + name + "» удалён");
                });
            }
            labels.add("Добавить сервер");
            actions.add(this::add);
        } else {
            labels.add("Управление мирами");
            actions.add(() -> client.setScreen(new SelectWorldScreen(this)));
            labels.add("Создать мир");
            actions.add(this::add);
        }
        labels.add("Классическое меню");
        actions.add(() -> {
            BlockyardUi.vanillaOnce = true;
            client.setScreen(new TitleScreen());
        });
        labels.add("Выйти из игры");
        actions.add(client::scheduleStop);

        float w = 0;
        for (String l : labels) w = Math.max(w, Ui.width(textRenderer, l, Ui.BODY));
        w += 16;
        float rowH = 10, h = labels.size() * rowH + 5;
        float x = menuX, y = Math.max(4, menuY - h - 4);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(0, 0, 300);
        Ui.shadow(ctx, x, y + 3, w, h, 10, 0.7f);
        Ui.round(ctx, x, y, w, h, 5.5f, 0xF41A1828);
        for (int i = 0; i < labels.size(); i++) {
            float ry = y + 2.5f + i * rowH;
            boolean hov = hoverAny(x, ry, w, rowH);
            if (hov) Ui.round(ctx, x + 2, ry, w - 4, rowH, 3.5f, 0x22FFFFFF);
            boolean danger = labels.get(i).startsWith("Удалить") || labels.get(i).startsWith("Выйти");
            Ui.text(ctx, textRenderer, labels.get(i), Ui.BODY, x + 8, ry + 2.9f, danger ? 0xFFFF8A8A : 0xFFFFFFFF);
            final Runnable action = actions.get(i);
            popupRegion(x, ry, w, rowH, () -> {
                click();
                closePopup();
                action.run();
            });
        }
        m.pop();
    }

    @Override
    protected void closePopup() {
        super.closePopup();
        menuOpen = false;
    }

    // ================================================================ keys

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modal && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closePopup();
            return true;
        }
        int n = count();
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT -> {
                if (tab != TAB_MODS && n > 0 && selected[sel()] < n - 1) select(selected[sel()] + 1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (tab != TAB_MODS && selected[sel()] > 0) select(selected[sel()] - 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (tab != TAB_MODS && current() >= 0) play(current());
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                click();
                openTab(tab == TAB_SINGLE ? TAB_MULTI : tab == TAB_MULTI ? TAB_MODS : TAB_SINGLE);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
