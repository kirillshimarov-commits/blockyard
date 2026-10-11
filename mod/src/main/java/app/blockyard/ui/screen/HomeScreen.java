package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Console-style main menu. Everything is laid out on a virtual canvas 270 units tall, scaled to the
 * window, so it looks the same at any GUI scale; shapes are smooth textures and text uses TTF fonts.
 */
public class HomeScreen extends Screen {
    private static final float CANVAS_H = 270f;
    private static final int TAB_SINGLE = 0, TAB_MULTI = 1;
    private static final int WHITE = 0xFFFFFF, INK = 0x0B0A12;
    private static final ExecutorService PINGER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Blockyard server pinger");
        t.setDaemon(true);
        return t;
    });

    // Remembered while the game runs, so coming back from a world keeps the place.
    private static boolean introDone = false;
    private static int tab = TAB_MULTI;
    private static final int[] selected = {0, 0};

    private final SpaceBackground background = new SpaceBackground();
    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final List<Region> regions = new ArrayList<>();
    private final Map<String, Identifier> icons = new HashMap<>();
    private final Map<String, Object> iconSources = new HashMap<>();
    ServerList servers;
    private List<LevelSummary> worlds = null;

    private boolean menuOpen = false;
    private float menuX, menuY;
    private long changedAt = System.currentTimeMillis();
    private long introLeaveAt = 0;
    private String flash = null;
    private long flashAt = 0;

    // Virtual canvas.
    private float k = 1, vw = 480;

    private record Region(float x, float y, float w, float h, Runnable action, boolean menu) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public HomeScreen() {
        super(Text.literal("Blockyard"));
    }

    @Override
    protected void init() {
        BlockyardUi.loadConfig();
        servers = new ServerList(client);
        servers.loadFile();
        for (int i = 0; i < servers.size(); i++) ping(servers.get(i));
        loadWorlds();
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

    void ping(ServerInfo info) {
        if (info.getStatus() != ServerInfo.Status.INITIAL) return;
        info.setStatus(ServerInfo.Status.PINGING);
        info.label = Text.empty();
        info.playerCountLabel = Text.empty();
        PINGER.submit(() -> {
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

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Drawn in render().
    }

    // ================================================================ entries

    private int count() {
        return tab == TAB_MULTI ? servers.size() : (worlds == null ? 0 : worlds.size());
    }

    private int current() {
        int n = count();
        if (n == 0) return -1;
        selected[tab] = Math.max(0, Math.min(selected[tab], n - 1));
        return selected[tab];
    }

    private String nameOf(int i) {
        if (tab == TAB_MULTI) return servers.get(i).name;
        LevelSummary w = worlds.get(i);
        return w.getDisplayName().isEmpty() ? w.getName() : w.getDisplayName();
    }

    private Identifier iconOf(int i) {
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(i);
            byte[] favicon = s.getFavicon();
            if (favicon == null) return null;
            String key = "s" + s.address;
            if (iconSources.get(key) != favicon) {
                iconSources.put(key, favicon);
                try {
                    register(key, NativeImage.read(favicon));
                } catch (Exception e) {
                    icons.remove(key);
                }
            }
            return icons.get(key);
        }
        LevelSummary w = worlds.get(i);
        String key = "w" + w.getName();
        if (!iconSources.containsKey(key)) {
            iconSources.put(key, Boolean.TRUE);
            Path path = w.getIconPath();
            if (path != null && Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    register(key, NativeImage.read(in));
                } catch (Exception ignored) {
                    // No icon then.
                }
            }
        }
        return icons.get(key);
    }

    private void register(String key, NativeImage image) {
        Identifier id = Identifier.of("blockyard-ui", "icons/" + Integer.toHexString(key.hashCode()));
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.setFilter(true, false);
        client.getTextureManager().registerTexture(id, texture);
        icons.put(key, id);
    }

    void play(int i) {
        click();
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

    void click() {
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void select(int i) {
        if (selected[tab] == i) return;
        selected[tab] = i;
        changedAt = System.currentTimeMillis();
        click();
    }

    private void switchTab(int target) {
        menuOpen = false;
        if (tab == target) return;
        click();
        tab = target;
        changedAt = System.currentTimeMillis();
    }

    private void notice(String text) {
        flash = text;
        flashAt = System.currentTimeMillis();
    }

    // ================================================================ rendering

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        regions.clear();
        int accent = BlockyardUi.accent;
        long now = System.currentTimeMillis();

        float introFade = introDone ? 1 : 0;
        if (introLeaveAt > 0) introFade = ease((now - introLeaveAt) / 450f);
        background.render(ctx, width, height, accent, 1f);

        k = height / CANVAS_H;
        vw = width / k;
        float mx = mouseX / k, my = mouseY / k;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);

        if (!introDone || introLeaveAt > 0 && introFade < 1) {
            drawIntro(ctx, mx, my, accent, 1 - introFade);
            if (introLeaveAt > 0 && introFade >= 1) {
                introDone = true;
                introLeaveAt = 0;
            }
        }
        if (introDone || introLeaveAt > 0) {
            float a = introDone && introLeaveAt == 0 ? 1 : introFade;
            drawTopBar(ctx, mx, my, accent, a);
            int idx = current();
            float rowBottom = drawTiles(ctx, mx, my, idx, accent, a);
            float appear = ease((now - changedAt) / 380f) * a;
            if (idx >= 0) drawHero(ctx, mx, my, idx, rowBottom, accent, appear);
            else drawEmpty(ctx, mx, my, rowBottom, accent, a);
            if (menuOpen) drawMenu(ctx, mx, my);
            if (flash != null && now - flashAt < 2600) {
                float fa = Math.min(1, (2600 - (now - flashAt)) / 400f);
                float w = Ui.width(textRenderer, flash, Ui.BODY) + 24;
                Ui.round(ctx, (vw - w) / 2, CANVAS_H - 34, w, 18, 9, Ui.argb(0.9f * fa, 0x1A1828));
                Ui.centered(ctx, textRenderer, flash, Ui.BODY, vw / 2, CANVAS_H - 28.5f, Ui.argb(fa, WHITE));
            }
        }
        m.pop();
    }

    private boolean hover(float mx, float my, float x, float y, float w, float h) {
        return !menuOpen && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void region(float x, float y, float w, float h, Runnable action) {
        regions.add(new Region(x, y, w, h, action, false));
    }

    // ---------------------------------------------------------------- intro

    private void drawIntro(DrawContext ctx, float mx, float my, int accent, float a) {
        if (a <= 0.01f) return;
        String clock = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + ", "
                + capitalize(LocalDate.now().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("ru")));
        Ui.text(ctx, textRenderer, clock, Ui.BODY, vw - 18 - Ui.width(textRenderer, clock, Ui.BODY), 14, Ui.argb(0.55f * a, WHITE));

        float cy = CANVAS_H * 0.40f;
        String hello = "Добро пожаловать в ";
        String brand = "Blockyard";
        float hw = Ui.width(textRenderer, hello, Ui.HEAD), bw = Ui.width(textRenderer, brand, Ui.HEAD);
        float x = (vw - hw - bw) / 2;
        Ui.text(ctx, textRenderer, hello, Ui.HEAD, x, cy, Ui.argb(0.92f * a, WHITE));
        Ui.text(ctx, textRenderer, brand, Ui.HEAD, x + hw, cy, Ui.argb(a, Ui.mix(accent, WHITE, 0.45f)));
        Ui.centered(ctx, textRenderer, "Во что сегодня играем?", Ui.BODY, vw / 2, cy + 17, Ui.argb(0.5f * a, WHITE));

        String[] icons = {"gear", "cube", "globe"};
        String[] labels = {"Настройки", "Одиночная игра", "Сетевая игра"};
        float r = 17, gap = 58, oy = cy + 58;
        float ox = vw / 2 - gap;
        for (int i = 0; i < 3; i++) {
            float cx = ox + i * gap;
            boolean hov = mx >= cx - r && mx < cx + r && my >= oy - r && my < oy + r && introLeaveAt == 0;
            float s = hov ? 1.12f : 1f;
            if (hov) Ui.light(ctx, Ui.tex("glow"), cx - r * 2.6f, oy - r * 2.6f, r * 5.2f, r * 5.2f, Ui.argb(0.45f * a, accent));
            Ui.circle(ctx, cx, oy, r * s + (hov ? 1.2f : 0), Ui.argb((hov ? 0.85f : 0) * a, WHITE));
            Ui.circle(ctx, cx, oy, r * s, Ui.argb((hov ? 0.30f : 0.10f) * a, WHITE));
            float is = 15 * s;
            Ui.icon(ctx, icons[i], cx - is / 2, oy - is / 2, is, Ui.argb((hov ? 1f : 0.55f) * a, WHITE));
            if (hov) Ui.centered(ctx, textRenderer, labels[i], Ui.BODY, cx, oy + r + 8, Ui.argb(a, WHITE));
            final int which = i;
            region(cx - r, oy - r, r * 2, r * 2, () -> {
                if (introLeaveAt > 0) return;
                click();
                if (which == 0) {
                    introDone = true;
                    client.setScreen(new OptionsScreen(this, client.options));
                    return;
                }
                tab = which == 1 ? TAB_SINGLE : TAB_MULTI;
                introLeaveAt = System.currentTimeMillis();
                changedAt = introLeaveAt + 200;
            });
        }
        Ui.centered(ctx, textRenderer, "Blockyard · Minecraft 1.21.1", Ui.BODY, vw / 2, CANVAS_H - 16, Ui.argb(0.22f * a, WHITE));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // ---------------------------------------------------------------- top bar

    private void drawTopBar(DrawContext ctx, float mx, float my, int accent, float a) {
        String[] labels = {"Одиночная игра", "Сетевая игра"};
        float x = 16, y = 10, h = 15;
        for (int i = 0; i < labels.length; i++) {
            float w = Ui.width(textRenderer, labels[i], Ui.BODY) + 18;
            boolean active = i == tab;
            boolean hov = hover(mx, my, x, y, w, h);
            if (active) {
                Ui.light(ctx, Ui.tex("glow"), x - 6, y - 8, w + 12, h + 16, Ui.argb(0.18f * a, WHITE));
                Ui.round(ctx, x, y, w, h, h / 2, Ui.argb(a, WHITE));
            } else if (hov) {
                Ui.round(ctx, x, y, w, h, h / 2, Ui.argb(0.10f * a, WHITE));
            }
            Ui.text(ctx, textRenderer, labels[i], Ui.BODY, x + 9, y + 4, Ui.argb(a * (active ? 1 : hov ? 0.95f : 0.72f), active ? INK : WHITE));
            final int target = i;
            region(x, y, w, h, () -> switchTab(target));
            x += w + 6;
        }

        String clock = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        float cw = Ui.width(textRenderer, clock, Ui.BOLD);
        float rx = vw - 16 - cw;
        Ui.text(ctx, textRenderer, clock, Ui.BOLD, rx, y + 4, Ui.argb(a, WHITE));
        float ax = rx - 12 - 7;
        Ui.circle(ctx, ax, y + h / 2, 7.5f, Ui.argb(a, Ui.mix(accent, WHITE, 0.08f)));
        String name = client.getSession().getUsername();
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Ui.centered(ctx, textRenderer, letter, Ui.BOLD, ax, y + 4, Ui.argb(a, WHITE));
        float gx = ax - 12 - 18;
        boolean hovGear = hover(mx, my, gx - 3, y - 2, 17, 19);
        if (hovGear) Ui.circle(ctx, gx + 5.5f, y + h / 2, 9, Ui.argb(0.12f * a, WHITE));
        Ui.icon(ctx, "gear", gx, y + 2, 11, Ui.argb((hovGear ? 1 : 0.85f) * a, WHITE));
        region(gx - 3, y - 2, 17, 19, () -> {
            click();
            client.setScreen(new OptionsScreen(this, client.options));
        });
    }

    // ---------------------------------------------------------------- tiles

    private float drawTiles(DrawContext ctx, float mx, float my, int idx, int accent, float a) {
        float base = 34, big = 46, gap = 8, left = 16;
        float center = 60;
        int n = count();
        float selX = left + (base + gap) * (idx + 1);
        float scroll = Math.max(0, selX + big + 24 - vw);
        float x = left - scroll;

        // "+" tile.
        boolean hovAdd = hover(mx, my, x, center - base / 2, base, base);
        Ui.round(ctx, x, center - base / 2, base, base, 9, Ui.argb((hovAdd ? 0.16f : 0.07f) * a, WHITE));
        Ui.icon(ctx, "plus", x + base / 2 - 6, center - 6, 12, Ui.argb((hovAdd ? 0.9f : 0.5f) * a, WHITE));
        region(x, center - base / 2, base, base, this::add);
        x += base + gap;

        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            boolean sel = i == idx;
            float size = sel ? big : base;
            float ty = center - size / 2;
            boolean hov = hover(mx, my, x, ty, size, size);
            if (hov && !sel) ty -= 2;
            float r = sel ? 11 : 9;
            if (sel) {
                float pulse = 0.85f + 0.15f * (float) Math.sin(now / 600.0);
                Ui.light(ctx, Ui.tex("glow"), x - size * 0.6f, ty - size * 0.5f, size * 2.2f, size * 2.2f, Ui.argb(0.42f * a * pulse, accent));
                Ui.shadow(ctx, x, ty + 4, size, size, 10, 0.6f * a);
                Ui.round(ctx, x - 2.5f, ty - 2.5f, size + 5, size + 5, r + 2.5f, Ui.argb(a, WHITE));
            } else {
                Ui.shadow(ctx, x, ty + 2, size, size, 6, 0.35f * a);
            }
            drawArt(ctx, i, x, ty, size, r, accent, a * (sel || hov ? 1 : 0.62f));
            final int target = i;
            region(x, ty, size, size, () -> {
                if (selected[tab] == target) play(target);
                else select(target);
            });
            x += size + gap;
        }

        float captionY = center + big / 2 + 9;
        if (idx >= 0) {
            float cx = left + base + gap - scroll;
            String tag = tab == TAB_MULTI ? "сервер" : "мир";
            float tw = Ui.width(textRenderer, tag, Ui.BOLD) + 10;
            Ui.round(ctx, cx, captionY, tw, 11, 5.5f, Ui.argb(a, WHITE));
            Ui.text(ctx, textRenderer, tag, Ui.BOLD, cx + 5, captionY + 2, Ui.argb(a, INK));
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, nameOf(idx), Ui.BODY, vw * 0.5f), Ui.BODY, cx + tw + 6, captionY + 2, Ui.argb(a, WHITE));
        }
        return captionY + 16;
    }

    /** The picture of an entry: its icon, or its first letter on an accent gradient. */
    private void drawArt(DrawContext ctx, int i, float x, float y, float size, float r, int accent, float a) {
        Identifier icon = iconOf(i);
        if (icon != null) {
            Ui.round(ctx, x, y, size, size, r, Ui.argb(a, 0x16141F));
            // Icons are square; inset a little so the rounded frame shows around them.
            float inset = size * 0.08f;
            Ui.image(ctx, icon, x + inset, y + inset, size - inset * 2, size - inset * 2, Ui.argb(a, WHITE));
        } else {
            Ui.round(ctx, x, y, size, size, r, Ui.argb(a, Ui.mix(accent, WHITE, 0.15f)), Ui.argb(a, Ui.mix(accent, 0x000000, 0.35f)));
            String name = nameOf(i);
            String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            MatrixStack m = ctx.getMatrices();
            m.push();
            float scale = size / 60f;
            m.translate(x + size / 2, y + size / 2 - 11 * scale, 0);
            m.scale(scale, scale, 1);
            Ui.centered(ctx, textRenderer, letter, Ui.TITLE, 0, 0, Ui.argb(a, WHITE));
            m.pop();
        }
    }

    // ---------------------------------------------------------------- hero

    private void drawHero(DrawContext ctx, float mx, float my, int idx, float top, int accent, float a) {
        float lift = (1 - a) * 10;
        float art = Math.min(118, Math.min(vw * 0.25f, CANVAS_H - top - 40));
        float artX = vw - art - 26, artY = top + (CANVAS_H - top - art) / 2 - 8 + lift * 0.5f;
        float left = 26;
        float maxText = artX - left - 24;

        // Big art with glow on the right.
        Ui.light(ctx, Ui.tex("glow"), artX - art * 0.5f, artY - art * 0.45f, art * 2, art * 2, Ui.argb(0.35f * a, accent));
        Ui.shadow(ctx, artX, artY + 8, art, art, 22, 0.75f * a);
        drawArt(ctx, idx, artX, artY, art, 18, accent, a);

        float y = top + Math.max(6, (CANVAS_H - top - 120) / 2) + lift;
        Ui.text(ctx, textRenderer, Ui.fit(textRenderer, nameOf(idx), Ui.TITLE, maxText), Ui.TITLE, left, y, Ui.argb(a, WHITE));
        y += 30;

        String detail, status, motd = null;
        int dot;
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(idx);
            detail = "IP: " + s.address;
            switch (s.getStatus()) {
                case SUCCESSFUL -> {
                    status = s.playerCountLabel.getString() + " игроков  ·  " + s.ping + " мс";
                    dot = 0x3EE08F;
                }
                case INCOMPATIBLE -> {
                    status = "Другая версия: " + s.version.getString();
                    dot = 0xF2B84B;
                }
                case UNREACHABLE -> {
                    status = "Сервер недоступен";
                    dot = 0xEF5B5B;
                }
                default -> {
                    status = "Опрашиваем сервер…";
                    dot = 0x9AA0B0;
                }
            }
            if (s.label != null && !s.label.getString().isBlank()) motd = s.label.getString().replace('\n', ' ').trim();
        } else {
            LevelSummary w = worlds.get(idx);
            detail = w.getDetails().getString();
            long last = w.getLastPlayed();
            if (last <= 0) status = "Ещё не запускался";
            else {
                LocalDate d = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate();
                LocalDate today = LocalDate.now();
                status = d.equals(today) ? "Играли сегодня"
                        : d.equals(today.minusDays(1)) ? "Играли вчера"
                        : "Играли " + d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")));
            }
            dot = 0x9AA0B0;
        }
        Ui.text(ctx, textRenderer, Ui.fit(textRenderer, detail, Ui.BODY, maxText), Ui.BODY, left, y, Ui.argb(0.55f * a, WHITE));
        y += 14;

        String chip = Ui.fit(textRenderer, status, Ui.BODY, maxText - 24);
        float chipW = Ui.width(textRenderer, chip, Ui.BODY) + 24;
        Ui.round(ctx, left, y, chipW, 15, 5, Ui.argb(0.10f * a, WHITE));
        Ui.light(ctx, Ui.tex("glow"), left + 3, y + 1.5f, 12, 12, Ui.argb(0.8f * a, dot));
        Ui.circle(ctx, left + 9, y + 7.5f, 2.2f, Ui.argb(a, dot));
        Ui.text(ctx, textRenderer, chip, Ui.BODY, left + 16, y + 4, Ui.argb(a, WHITE));
        if (motd != null) {
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, motd, Ui.BODY, maxText), Ui.BODY, left, y + 21, Ui.argb(0.45f * a, WHITE));
        }

        // Buttons pinned near the bottom, like on a console.
        float by = CANVAS_H - 44 + lift;
        String label = "Играть";
        float pw = Ui.width(textRenderer, label, Ui.BOLD) + 40, ph = 21;
        boolean hovPlay = hover(mx, my, left, by, pw, ph);
        float grow = hovPlay ? 1.5f : 0;
        if (hovPlay) Ui.light(ctx, Ui.tex("glow"), left - 20, by - 18, pw + 40, ph + 36, Ui.argb(0.55f * a, accent));
        Ui.shadow(ctx, left, by + 3, pw, ph, 8, 0.5f * a);
        Ui.round(ctx, left - grow, by - grow, pw + grow * 2, ph + grow * 2, ph / 2 + grow, Ui.argb(a, WHITE));
        Ui.text(ctx, textRenderer, label, Ui.BOLD, left + 14, by + 6.5f, Ui.argb(a, INK));
        Ui.icon(ctx, "play", left + pw - 21, by + 6, 9, Ui.argb(a, INK));
        region(left, by, pw, ph, () -> play(idx));

        float dx = left + pw + 9, dr = ph / 2;
        boolean hovMore = hover(mx, my, dx, by, ph, ph);
        Ui.circle(ctx, dx + dr, by + dr, dr, Ui.argb((hovMore ? 0.22f : 0.11f) * a, WHITE));
        for (int d = -1; d <= 1; d++) Ui.circle(ctx, dx + dr + d * 3.6f, by + dr, 1.15f, Ui.argb(a, WHITE));
        region(dx, by, ph, ph, () -> {
            click();
            menuOpen = true;
            menuX = dx;
            menuY = by;
        });
    }

    private void drawEmpty(DrawContext ctx, float mx, float my, float top, int accent, float a) {
        float cy = top + (CANVAS_H - top) / 2 - 26;
        boolean loading = tab == TAB_SINGLE && worlds == null;
        String title = tab == TAB_MULTI ? "Серверов пока нет" : loading ? "Загружаем миры…" : "Миров пока нет";
        Ui.centered(ctx, textRenderer, title, Ui.HEAD, vw / 2, cy, Ui.argb(a, WHITE));
        if (loading) return;
        Ui.centered(ctx, textRenderer, tab == TAB_MULTI ? "Добавьте сервер по IP-адресу" : "Создайте свой первый мир", Ui.BODY, vw / 2, cy + 16, Ui.argb(0.5f * a, WHITE));
        String label = tab == TAB_MULTI ? "Добавить сервер" : "Создать мир";
        float bw = Ui.width(textRenderer, label, Ui.BOLD) + 30, bh = 21, bx = (vw - bw) / 2, by = cy + 34;
        boolean hov = hover(mx, my, bx, by, bw, bh);
        if (hov) Ui.light(ctx, Ui.tex("glow"), bx - 20, by - 18, bw + 40, bh + 36, Ui.argb(0.5f * a, accent));
        Ui.round(ctx, bx, by, bw, bh, bh / 2, Ui.argb(a, WHITE));
        Ui.centered(ctx, textRenderer, label, Ui.BOLD, vw / 2, by + 6.5f, Ui.argb(a, INK));
        region(bx, by, bw, bh, this::add);
    }

    // ---------------------------------------------------------------- menu

    private void drawMenu(DrawContext ctx, float mx, float my) {
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
            labels.add("Все миры");
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
        w += 26;
        float rowH = 15, h = labels.size() * rowH + 8;
        float x = menuX, y = Math.max(6, menuY - h - 6);
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(0, 0, 300);
        Ui.shadow(ctx, x, y + 4, w, h, 14, 0.7f);
        Ui.round(ctx, x, y, w, h, 9, 0xF21A1828);
        for (int i = 0; i < labels.size(); i++) {
            float ry = y + 4 + i * rowH;
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            if (hov) Ui.round(ctx, x + 3, ry, w - 6, rowH, 6, 0x22FFFFFF);
            boolean danger = labels.get(i).startsWith("Удалить") || labels.get(i).startsWith("Выйти");
            Ui.text(ctx, textRenderer, labels.get(i), Ui.BODY, x + 13, ry + 4, danger ? 0xFFFF8A8A : 0xFFFFFFFF);
            final Runnable action = actions.get(i);
            regions.add(new Region(x, ry, w, rowH, () -> {
                click();
                menuOpen = false;
                action.run();
            }, true));
        }
        m.pop();
    }

    // ================================================================ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double mx = mouseX / k, my = mouseY / k;
        // Later regions are drawn on top (the menu), so check them first.
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (menuOpen && !r.menu()) continue;
            if (r.contains(mx, my)) {
                r.action().run();
                return true;
            }
        }
        if (menuOpen) {
            menuOpen = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (menuOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menuOpen = false;
            return true;
        }
        if (!introDone) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                introLeaveAt = System.currentTimeMillis();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        int n = count();
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT -> {
                if (n > 0 && selected[tab] < n - 1) select(selected[tab] + 1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (selected[tab] > 0) select(selected[tab] - 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (current() >= 0) play(current());
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                switchTab(tab == TAB_MULTI ? TAB_SINGLE : TAB_MULTI);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }
}
