package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.AddServerScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
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

/** Console-style main menu: tabs, a row of world/server tiles and a large card for the selected one. */
public class HomeScreen extends Screen {
    private static final int TAB_SINGLE = 0, TAB_MULTI = 1;
    private static final ExecutorService PINGER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Blockyard server pinger");
        t.setDaemon(true);
        return t;
    });

    // Remembered while the game runs, so coming back from a world keeps the place.
    private static int tab = TAB_MULTI;
    private static final int[] selected = {0, 0};

    private final SpaceBackground background = new SpaceBackground();
    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final List<Region> regions = new ArrayList<>();
    private final Map<String, Identifier> icons = new HashMap<>();
    private final Map<String, byte[]> iconSources = new HashMap<>();
    private ServerList servers;
    private List<LevelSummary> worlds = null;
    private boolean menuOpen = false;
    private long openedAt;

    private record Region(int x, int y, int w, int h, Runnable action) {
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
        openedAt = System.currentTimeMillis();
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

    private void ping(ServerInfo info) {
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
        // Everything is drawn in render().
    }

    // ---------------------------------------------------------------- entries

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
            iconSources.put(key, new byte[0]);
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
        client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
        icons.put(key, id);
    }

    private void play(int i) {
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
        if (tab == TAB_MULTI) {
            ServerInfo info = new ServerInfo(Text.translatable("selectServer.defaultName").getString(), "", ServerInfo.ServerType.OTHER);
            client.setScreen(new AddServerScreen(this, confirmed -> {
                if (confirmed) {
                    servers.add(info, false);
                    servers.saveFile();
                    selected[TAB_MULTI] = servers.size() - 1;
                }
                client.setScreen(new HomeScreen());
            }, info));
        } else {
            CreateWorldScreen.create(client, this);
        }
    }

    private void click() {
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        regions.clear();
        int accent = BlockyardUi.accent;
        background.render(ctx, width, height, accent);
        float appear = Math.min(1f, (System.currentTimeMillis() - openedAt) / 400f);

        drawTopBar(ctx, mouseX, mouseY);
        int idx = current();
        int rowBottom = drawTiles(ctx, mouseX, mouseY, idx, accent, appear);
        if (idx >= 0) drawHero(ctx, mouseX, mouseY, idx, rowBottom, accent, appear);
        else drawEmpty(ctx, mouseX, mouseY, rowBottom);

        ctx.drawText(textRenderer, "← → выбор   Enter играть   Tab вкладки", 14, height - 14, 0x66FFFFFF, false);
        String brand = "Blockyard";
        ctx.drawText(textRenderer, brand, width - 14 - textRenderer.getWidth(brand), height - 14, 0x55FFFFFF, false);

        if (menuOpen) drawMenu(ctx, mouseX, mouseY);
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return !menuOpen && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void drawTopBar(DrawContext ctx, int mx, int my) {
        String[] labels = {"Одиночная игра", "Сетевая игра"};
        int x = 12, y = 10, h = 20;
        int total = 4;
        for (String l : labels) total += textRenderer.getWidth(l) + 18;
        Draw.round(ctx, x, y, total, h, 10, 0x1AFFFFFF);
        int cx = x + 2;
        for (int i = 0; i < labels.length; i++) {
            int w = textRenderer.getWidth(labels[i]) + 16;
            boolean active = i == tab;
            boolean hov = hover(mx, my, cx, y + 2, w, h - 4);
            if (active) Draw.round(ctx, cx, y + 2, w, h - 4, 8, 0xFFFFFFFF);
            else if (hov) Draw.round(ctx, cx, y + 2, w, h - 4, 8, 0x1FFFFFFF);
            ctx.drawText(textRenderer, labels[i], cx + 8, y + 6, active ? 0xFF0B0A12 : 0xC0FFFFFF, false);
            final int target = i;
            regions.add(new Region(cx, y + 2, w, h - 4, () -> switchTab(target)));
            cx += w + 2;
        }

        // Right side: settings, avatar, clock.
        String clock = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String settings = "Настройки";
        int sw = textRenderer.getWidth(settings) + 16;
        int cw = textRenderer.getWidth(clock);
        int boxW = 4 + sw + 6 + 16 + 8 + cw + 10;
        int bx = width - 12 - boxW;
        Draw.round(ctx, bx, y, boxW, h, 10, 0x1AFFFFFF);
        int sx = bx + 2;
        if (hover(mx, my, sx, y + 2, sw, h - 4)) Draw.round(ctx, sx, y + 2, sw, h - 4, 8, 0x1FFFFFFF);
        ctx.drawText(textRenderer, settings, sx + 8, y + 6, 0xE0FFFFFF, false);
        regions.add(new Region(sx, y + 2, sw, h - 4, () -> {
            click();
            client.setScreen(new OptionsScreen(this, client.options));
        }));
        int ax = sx + sw + 6;
        Draw.round(ctx, ax, y + 2, 16, 16, 8, Draw.argb(255, BlockyardUi.accent));
        String name = client.getSession().getUsername();
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        ctx.drawText(textRenderer, letter, ax + 8 - textRenderer.getWidth(letter) / 2, y + 6, 0xFFFFFFFF, false);
        ctx.drawText(textRenderer, clock, ax + 16 + 8, y + 6, 0xFFFFFFFF, false);
    }

    private void switchTab(int target) {
        if (tab == target) return;
        click();
        tab = target;
        menuOpen = false;
        openedAt = System.currentTimeMillis();
    }

    /** Draws the tile row; returns the y just below it. */
    private int drawTiles(DrawContext ctx, int mx, int my, int idx, int accent, float appear) {
        int base = 30, big = 46, gap = 8;
        int rowY = 44, center = rowY + big / 2;
        int n = count();
        int total = (base + gap) * (n + 1) + (big - base);
        int x = 18;
        // Keep the selected tile on screen when there are many.
        int selX = 18 + (base + gap) * (idx + 1);
        if (total + 36 > width && selX + big + 18 > width) x -= selX + big + 18 - width;

        // "+" tile.
        boolean hovAdd = hover(mx, my, x, center - base / 2, base, base);
        Draw.round(ctx, x, center - base / 2, base, base, 6, hovAdd ? 0x33FFFFFF : 0x1AFFFFFF);
        ctx.drawText(textRenderer, "+", x + base / 2 - 2, center - 4, 0xDDFFFFFF, false);
        regions.add(new Region(x, center - base / 2, base, base, this::add));
        x += base + gap;

        for (int i = 0; i < n; i++) {
            boolean sel = i == idx;
            int size = sel ? big : base;
            int ty = center - size / 2 + (int) ((1 - appear) * 10);
            boolean hov = hover(mx, my, x, ty, size, size);
            if (sel) {
                // Soft glow and a white ring around the selected tile.
                Draw.round(ctx, x - 8, ty - 4, size + 16, size + 16, 14, Draw.argb(40, accent));
                Draw.round(ctx, x - 3, ty - 3, size + 6, size + 6, 10, 0x30FFFFFF);
                Draw.round(ctx, x - 2, ty - 2, size + 4, size + 4, 9, 0xFFFFFFFF);
            }
            drawIcon(ctx, i, x, hov && !sel ? ty - 2 : ty, size, sel ? 7 : 6, accent, sel || hov ? 255 : 190);
            final int target = i;
            regions.add(new Region(x, ty, size, size, () -> {
                if (selected[tab] == target) play(target);
                else {
                    click();
                    selected[tab] = target;
                    openedAt = System.currentTimeMillis();
                }
            }));
            x += size + gap;
        }

        int captionY = rowY + big + 10;
        if (idx >= 0) {
            String tag = tab == TAB_MULTI ? "сервер" : "мир";
            int tagW = textRenderer.getWidth(tag) + 10;
            int cx = 18 + base + gap;
            Draw.round(ctx, cx, captionY - 2, tagW, 12, 6, 0xFFFFFFFF);
            ctx.drawText(textRenderer, tag, cx + 5, captionY, 0xFF0B0A12, false);
            ctx.drawText(textRenderer, Draw.ellipsize(textRenderer, nameOf(idx), width / 2), cx + tagW + 6, captionY, 0xFFFFFFFF, false);
        }
        return captionY + 14;
    }

    private void drawIcon(DrawContext ctx, int i, int x, int y, int size, int r, int accent, int alpha) {
        Identifier icon = iconOf(i);
        if (icon != null) {
            Draw.round(ctx, x, y, size, size, r, 0xFF111018);
            ctx.drawTexture(icon, x + 1, y + 1, size - 2, size - 2, 0, 0, 64, 64, 64, 64);
            if (alpha < 255) ctx.fill(x, y, x + size, y + size, Draw.argb(255 - alpha, 0x0B0A12));
        } else {
            int top = Draw.mix(accent, 0xFFFFFF, 0.1f), bottom = Draw.mix(accent, 0x000000, 0.35f);
            Draw.round(ctx, x, y, size, size, r, Draw.argb(alpha, Draw.mix(top, bottom, 0.5f)));
            String name = nameOf(i);
            String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            float scale = size / 16f;
            float lw = textRenderer.getWidth(letter) * scale;
            Draw.text(ctx, textRenderer, letter, x + (size - lw) / 2f, y + (size - 8 * scale) / 2f, scale, Draw.argb(alpha, 0xFFFFFF));
        }
    }

    private void drawHero(DrawContext ctx, int mx, int my, int idx, int top, int accent, float appear) {
        int artSize = (int) Math.max(64, Math.min(height * 0.42f, width * 0.26f));
        int artX = width - artSize - 28;
        int left = 28;
        int maxText = artX - left - 24;
        int lift = (int) ((1 - appear) * 12);
        int alpha = (int) (255 * appear);

        float scale = Math.max(2f, Math.min(4f, width / 170f));
        int heroH = (int) (8 * scale) + 12 + 14 + 18 + 28;
        int y = top + Math.max(10, (height - 24 - top - heroH) / 2) + lift;

        // Big art on the right with a soft glow.
        int artY = top + Math.max(6, (height - 24 - top - artSize) / 2) - lift;
        Draw.round(ctx, artX - 12, artY - 8, artSize + 24, artSize + 24, 22, Draw.argb((int) (36 * appear), accent));
        drawIcon(ctx, idx, artX, artY, artSize, 14, accent, alpha);

        String title = Draw.ellipsize(textRenderer, nameOf(idx), (int) (maxText / scale));
        Draw.text(ctx, textRenderer, title, left, y, scale, Draw.argb(alpha, 0xFFFFFF));
        y += (int) (8 * scale) + 10;

        String detail;
        String status;
        int dot;
        if (tab == TAB_MULTI) {
            ServerInfo s = servers.get(idx);
            detail = "IP: " + s.address;
            switch (s.getStatus()) {
                case SUCCESSFUL -> {
                    status = s.playerCountLabel.getString() + "  ·  " + s.ping + " мс";
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
        ctx.drawText(textRenderer, Draw.ellipsize(textRenderer, detail, maxText), left, y, Draw.argb((int) (170 * appear), 0xFFFFFF), false);
        y += 16;

        String chip = Draw.ellipsize(textRenderer, status, maxText - 20);
        int chipW = textRenderer.getWidth(chip) + 22;
        Draw.round(ctx, left, y, chipW, 16, 5, Draw.argb((int) (30 * appear), 0xFFFFFF));
        Draw.round(ctx, left + 7, y + 6, 4, 4, 2, Draw.argb(alpha, dot));
        ctx.drawText(textRenderer, chip, left + 15, y + 4, Draw.argb(alpha, 0xFFFFFF), false);
        if (tab == TAB_MULTI) {
            Text motd = servers.get(idx).label;
            if (motd != null && !motd.getString().isBlank()) {
                ctx.drawText(textRenderer, Draw.ellipsize(textRenderer, motd.getString().replace('\n', ' '), maxText), left, y + 22, Draw.argb((int) (140 * appear), 0xFFFFFF), false);
            }
        }
        y += 40;

        // Play and more buttons.
        String playLabel = "Играть  ▶";
        int pw = textRenderer.getWidth(playLabel) + 28, ph = 22;
        boolean hovPlay = hover(mx, my, left, y, pw, ph);
        if (hovPlay) Draw.round(ctx, left - 3, y - 3, pw + 6, ph + 6, 14, Draw.argb(60, accent));
        Draw.round(ctx, left, y, pw, ph, 11, 0xFFFFFFFF);
        ctx.drawText(textRenderer, playLabel, left + 14, y + 7, 0xFF0B0A12, false);
        regions.add(new Region(left, y, pw, ph, () -> play(idx)));

        final int btnY = y;
        int mxBtn = left + pw + 8;
        boolean hovMore = hover(mx, my, mxBtn, y, ph, ph);
        Draw.round(ctx, mxBtn, y, ph, ph, 11, hovMore ? 0x40FFFFFF : 0x24FFFFFF);
        ctx.drawText(textRenderer, "•••", mxBtn + ph / 2 - textRenderer.getWidth("•••") / 2, y + 7, 0xFFFFFFFF, false);
        regions.add(new Region(mxBtn, y, ph, ph, () -> {
            click();
            menuOpen = true;
            menuX = mxBtn;
            menuY = btnY;
        }));
    }

    private int menuX, menuY;

    private void drawEmpty(DrawContext ctx, int mx, int my, int top) {
        int cy = top + (height - top) / 2 - 30;
        String title = tab == TAB_MULTI ? "Серверов пока нет" : (worlds == null ? "Загружаем миры…" : "Миров пока нет");
        float scale = 2f;
        Draw.text(ctx, textRenderer, title, (width - textRenderer.getWidth(title) * scale) / 2f, cy, scale, 0xFFFFFFFF);
        if (worlds == null && tab == TAB_SINGLE) return;
        String label = tab == TAB_MULTI ? "Добавить сервер" : "Создать мир";
        int bw = textRenderer.getWidth(label) + 28, bh = 22;
        int bx = (width - bw) / 2, by = cy + 30;
        boolean hov = hover(mx, my, bx, by, bw, bh);
        if (hov) Draw.round(ctx, bx - 3, by - 3, bw + 6, bh + 6, 14, Draw.argb(60, BlockyardUi.accent));
        Draw.round(ctx, bx, by, bw, bh, 11, 0xFFFFFFFF);
        ctx.drawText(textRenderer, label, bx + 14, by + 7, 0xFF0B0A12, false);
        regions.add(new Region(bx, by, bw, bh, this::add));
    }

    private void drawMenu(DrawContext ctx, int mx, int my) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (tab == TAB_MULTI) {
            labels.add("Управление серверами");
            actions.add(() -> client.setScreen(new MultiplayerScreen(this)));
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

        int w = 0;
        for (String l : labels) w = Math.max(w, textRenderer.getWidth(l));
        w += 24;
        int rowH = 16, h = labels.size() * rowH + 8;
        int x = menuX, y = Math.max(4, menuY - h - 6);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 200);
        Draw.round(ctx, x, y, w, h, 8, 0xF0141220);
        for (int i = 0; i < labels.size(); i++) {
            int ry = y + 4 + i * rowH;
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            if (hov) Draw.round(ctx, x + 3, ry, w - 6, rowH, 5, 0x22FFFFFF);
            ctx.drawText(textRenderer, labels.get(i), x + 12, ry + 4, 0xFFFFFFFF, false);
            final Runnable action = actions.get(i);
            regions.add(new Region(x, ry, w, rowH, () -> {
                click();
                menuOpen = false;
                action.run();
            }));
        }
        ctx.getMatrices().pop();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        // Later regions are drawn on top (the menu), so check them first.
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.contains(mouseX, mouseY)) {
                if (menuOpen && i < regions.size() - menuItemCount()) break;
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

    private int menuItemCount() {
        return 4;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (menuOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            menuOpen = false;
            return true;
        }
        int n = count();
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT -> {
                if (n > 0 && selected[tab] < n - 1) {
                    selected[tab]++;
                    click();
                    openedAt = System.currentTimeMillis();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (selected[tab] > 0) {
                    selected[tab]--;
                    click();
                    openedAt = System.currentTimeMillis();
                }
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
