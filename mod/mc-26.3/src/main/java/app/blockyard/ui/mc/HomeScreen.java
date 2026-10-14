package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.PlatformImpl;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import com.mojang.blaze3d.platform.InputConstants;

import java.awt.image.BufferedImage;
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

/** The main menu: {@link HomeView} with the game's worlds, servers, mods and screenshots. */
public class HomeScreen extends UiScreen implements HomeView.Model {
    // Remembered while the game runs, so coming back keeps the place.
    private static int tab = HomeView.SINGLE;
    private static final int[] selected = {0, 0};

    private final ServerStatusPinger pinger = new ServerStatusPinger();
    ServerList servers;
    private List<LevelSummary> worlds = null;
    private List<HomeView.Mod> mods = List.of();
    private List<Path> screenshots = List.of();
    private int screenshotCount = 0;
    private final HomeView.State state = new HomeView.State();

    public HomeScreen() {
        super(Component.literal("Blockyard"));
    }

    public HomeScreen(int tab) {
        this();
        if (tab == HomeView.SINGLE || tab == HomeView.MULTI || tab == HomeView.MODS) HomeScreen.tab = tab;
    }

    @Override
    protected void init() {
        super.init();
        if (servers == null) {
            servers = new ServerList(minecraft);
            servers.load();
            for (int i = 0; i < servers.size(); i++) ping(servers.get(i));
            loadWorlds();
            mods = loadMods();
            loadScreenshots();
        }
    }

    private void loadWorlds() {
        try {
            LevelStorageSource storage = minecraft.getLevelSource();
            storage.loadLevelSummaries(storage.findLevelCandidates()).thenAcceptAsync(list -> {
                List<LevelSummary> sorted = new ArrayList<>(list);
                sorted.sort(Comparator.comparingLong(LevelSummary::getLastPlayed).reversed());
                worlds = sorted;
            }, minecraft);
        } catch (Exception e) {
            worlds = List.of();
        }
    }

    static List<HomeView.Mod> loadMods() {
        return PlatformImpl.mods();
    }

    /** Mod icons arrive later; the list is rebuilt with whatever is loaded. */
    private List<HomeView.Mod> modsWithIcons() {
        List<HomeView.Mod> out = new ArrayList<>(mods.size());
        for (HomeView.Mod m : mods) out.add(new HomeView.Mod(m.id(), m.name(), m.version(), m.description(), modIcon(m.id())));
        return out;
    }

    static BufferedImage modIcon(String id) {
        return Images.file("m:" + id, PlatformImpl.modIcon(id));
    }

    private void loadScreenshots() {
        Path dir = minecraft.gameDirectory.toPath().resolve("screenshots");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.filter(p -> p.toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            screenshotCount = all.size();
            screenshots = all.subList(0, Math.min(6, all.size()));
        } catch (Exception ignored) {
            // No screenshots then.
        }
    }

    void ping(ServerData info) {
        if (info.state() != ServerData.State.INITIAL) return;
        info.setState(ServerData.State.PINGING);
        info.motd = Component.empty();
        info.status = Component.empty();
        Images.WORKER.submit(() -> {
            try {
                pinger.pingServer(info, () -> minecraft.execute(servers::save), () -> info.setState(ServerData.State.SUCCESSFUL),
                        EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
            } catch (Exception e) {
                info.setState(ServerData.State.UNREACHABLE);
            }
        });
    }

    @Override
    public void tick() {
        pinger.tick();
    }

    @Override
    public void removed() {
        pinger.removeAll();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void draw(Frame f, float delta) {
        HomeView.draw(f, this, state);
    }

    @Override
    protected boolean modal() {
        return state.menuOpen;
    }

    @Override
    protected void closePopup() {
        state.menuOpen = false;
    }

    // ================================================================ model

    private int count() {
        return tab == HomeView.MULTI ? servers.size() : (worlds == null ? 0 : worlds.size());
    }

    private int slot() {
        return tab == HomeView.MULTI ? 1 : 0;
    }

    public int tab() {
        return tab;
    }

    public void openTab(int target) {
        state.menuOpen = false;
        if (target == HomeView.PACKS) {
            minecraft.gui.setScreen(new SettingsScreen(this, SettingsScreen.PACKS));
            return;
        }
        if (tab == target) return;
        tab = target;
        state.modsScroll = 0;
        state.changedAt = System.currentTimeMillis();
    }

    public List<HomeView.Entry> entries() {
        List<HomeView.Entry> out = new ArrayList<>();
        int n = count();
        for (int i = 0; i < n; i++) out.add(tab == HomeView.MULTI ? server(servers.get(i)) : world(worlds.get(i)));
        return out;
    }

    private HomeView.Entry server(ServerData s) {
        List<String> chips = new ArrayList<>();
        int dot = 0x9AA0B0;
        String motd = null;
        switch (s.state()) {
            case SUCCESSFUL -> {
                chips.add("Онлайн: " + s.status.getString());
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
        if (s.motd != null && !s.motd.getString().isBlank()) motd = s.motd.getString().replace('\n', ' ').trim();
        String key = "s:" + s.ip;
        return new HomeView.Entry(key, s.name, "IP: " + s.ip, chips, dot, motd, Images.bytes(key, s.getIconBytes()));
    }

    private HomeView.Entry world(LevelSummary w) {
        List<String> chips = new ArrayList<>();
        long last = w.getLastPlayed();
        if (last <= 0) chips.add("Ещё не запускался");
        else {
            LocalDate d = Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate today = LocalDate.now();
            chips.add(d.equals(today) ? "Играли сегодня" : d.equals(today.minusDays(1)) ? "Играли вчера"
                    : "Играли " + d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))));
        }
        if (w.isHardcore()) chips.add("Хардкор");
        if (w.hasCommands()) chips.add("Читы");
        String name = w.getLevelName().isEmpty() ? w.getLevelId() : w.getLevelName();
        String key = "w:" + w.getLevelId();
        return new HomeView.Entry(key, name, w.getInfo().getString(), chips, 0x9AA0B0, null, Images.file(key, w.getIcon()));
    }

    public int selected() {
        int n = count();
        if (n == 0) return tab == HomeView.SINGLE && worlds == null ? -1 : 0;
        selected[slot()] = Math.max(0, Math.min(selected[slot()], n - 1));
        return selected[slot()];
    }

    public void select(int i) {
        if (selected[slot()] == i) return;
        selected[slot()] = i;
        state.changedAt = System.currentTimeMillis();
    }

    public void play(int i) {
        if (i < 0 || i >= count()) return;
        HomeView.Entry e = tab == HomeView.MULTI ? server(servers.get(i)) : world(worlds.get(i));
        BlockyardUi.loadingTitle = e.name();
        BlockyardUi.loadingIcon = e.icon();
        BlockyardUi.loadingKey = e.key();
        if (tab == HomeView.MULTI) {
            ServerData s = servers.get(i);
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.ip), s, false, null);
        } else {
            minecraft.createWorldOpenFlows().openWorld(worlds.get(i).getLevelId(), () -> minecraft.gui.setScreen(new HomeScreen()));
        }
    }

    public void add() {
        if (tab == HomeView.MULTI) minecraft.gui.setScreen(new ServerDialog(this, -1));
        else CreateWorldScreen.openFresh(minecraft, () -> minecraft.gui.setScreen(this));
    }

    public void settings() {
        minecraft.gui.setScreen(new SettingsScreen(this, SettingsScreen.GENERAL));
    }

    public void friend() {
        tab = HomeView.SINGLE;
        state.changedAt = System.currentTimeMillis();
    }

    public String user() {
        return minecraft.getUser().getName();
    }

    public List<HomeView.Mod> mods() {
        return modsWithIcons();
    }

    public List<HomeView.Shot> shots() {
        List<HomeView.Shot> out = new ArrayList<>();
        for (Path p : screenshots) {
            String key = "shot:" + p;
            out.add(new HomeView.Shot(key, Images.thumbnail(key, p, 192, 120)));
        }
        return out;
    }

    public int shotCount() {
        return screenshotCount;
    }

    public void openShots() {
        File dir = minecraft.gameDirectory.toPath().resolve("screenshots").toFile();
        dir.mkdirs();
        com.mojang.blaze3d.Blaze3D.openPath(dir.toPath());
    }

    public void playAndInvite() {
        int i = selected();
        if (i < 0) return;
        BlockyardUi.inviteOnJoin = true;
        play(i);
    }

    public List<HomeView.MenuItem> menu() {
        List<HomeView.MenuItem> items = new ArrayList<>();
        int idx = selected();
        if (tab == HomeView.MULTI) {
            if (idx >= 0 && idx < count()) {
                items.add(new HomeView.MenuItem("Изменить сервер", false, () -> minecraft.gui.setScreen(new ServerDialog(this, idx))));
                items.add(new HomeView.MenuItem("Обновить статус", false, () -> {
                    ServerData s = servers.get(idx);
                    s.setState(ServerData.State.INITIAL);
                    ping(s);
                }));
                items.add(new HomeView.MenuItem("Удалить сервер", true, () -> {
                    servers.remove(servers.get(idx));
                    servers.save();
                }));
            }
            items.add(new HomeView.MenuItem("Добавить сервер", false, this::add));
        } else {
            items.add(new HomeView.MenuItem("Управление мирами", false, () -> minecraft.gui.setScreen(new SelectWorldScreen(this))));
            items.add(new HomeView.MenuItem("Создать мир", false, this::add));
        }
        items.add(new HomeView.MenuItem("Классическое меню", false, () -> {
            BlockyardUi.vanillaOnce = true;
            minecraft.gui.setScreen(new TitleScreen());
        }));
        items.add(new HomeView.MenuItem("Выйти из игры", true, minecraft::stop));
        return items;
    }

    public boolean loading() {
        return tab == HomeView.SINGLE && worlds == null;
    }

    // ================================================================ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == HomeView.MODS) {
            state.modsScroll -= (float) verticalAmount * 18;
            return true;
        }
        int n = count();
        if (verticalAmount != 0 && n > 0) {
            int next = selected() + (verticalAmount < 0 ? 1 : -1);
            if (next >= 0 && next < n) select(next);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        if (state.menuOpen && keyCode == InputConstants.KEY_ESCAPE) {
            state.menuOpen = false;
            return true;
        }
        int n = count();
        switch (keyCode) {
            case InputConstants.KEY_RIGHT -> {
                if (tab != HomeView.MODS && n > 0 && selected() < n - 1) select(selected() + 1);
                return true;
            }
            case InputConstants.KEY_LEFT -> {
                if (tab != HomeView.MODS && selected() > 0) select(selected() - 1);
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (tab != HomeView.MODS && n > 0) play(selected());
                return true;
            }
            case InputConstants.KEY_TAB -> {
                click();
                openTab(tab == HomeView.SINGLE ? HomeView.MULTI : tab == HomeView.MULTI ? HomeView.MODS : HomeView.SINGLE);
                return true;
            }
            default -> {
                return super.keyPressed(input);
            }
        }
    }
}
