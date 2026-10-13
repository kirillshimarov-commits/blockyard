package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.HomeView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Development only (BLOCKYARD_SELFTEST=1): walks through every screen, saves a screenshot of each
 * and quits, so CI can show how the menus really look in the game.
 */
public final class Selftest {
    public static final boolean ENABLED = System.getenv("BLOCKYARD_SELFTEST") != null;

    private record Step(String name, Runnable action, BooleanSupplier ready, int settle) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index = -1;
    private static int wait = 0;
    private static int total = 0;
    private static HomeScreen home;

    private Selftest() {}

    private static void step(String name, Runnable action, BooleanSupplier ready, int settle) {
        STEPS.add(new Step(name, action, ready, settle));
    }

    private static void build(MinecraftClient mc) {
        BooleanSupplier always = () -> true;
        step(null, () -> {}, () -> mc.currentScreen instanceof HomeScreen && mc.getOverlay() == null, 60);
        step("01-home", () -> home = (HomeScreen) mc.currentScreen, always, 30);
        step("02-home-multiplayer", () -> home.openTab(HomeView.MULTI), always, 30);
        step("03-home-mods", () -> home.openTab(HomeView.MODS), always, 30);
        step("04-server-dialog", () -> {
            home.openTab(HomeView.MULTI);
            mc.setScreen(new ServerDialog(home, -1));
        }, always, 30);
        step("05-settings-general", () -> {
            home.openTab(HomeView.SINGLE);
            mc.setScreen(new SettingsScreen(home, SettingsScreen.GENERAL));
        }, always, 30);
        step("06-settings-video", () -> ((SettingsScreen) mc.currentScreen).section(SettingsScreen.VIDEO), always, 30);
        step("07-settings-sound", () -> ((SettingsScreen) mc.currentScreen).section(SettingsScreen.SOUND), always, 30);
        step("08-settings-packs", () -> ((SettingsScreen) mc.currentScreen).section(SettingsScreen.PACKS), always, 30);
        step("09-vanilla-options", () -> {
            BlockyardUi.vanillaOptions = true;
            mc.setScreen(new OptionsScreen(home, mc.options));
        }, always, 30);
        step("10-vanilla-video", () -> mc.setScreen(new VideoOptionsScreen(mc.currentScreen, mc.options)), always, 30);
        step("11-vanilla-keybinds", () -> mc.setScreen(new KeybindsScreen(home, mc.options)), always, 30);
        step("12-create-world", () -> CreateWorldScreen.create(mc, home), () -> mc.currentScreen instanceof CreateWorldScreen, 40);
        step("13-loading", () -> press(mc.currentScreen, "selectWorld.create"), () -> !(mc.currentScreen instanceof CreateWorldScreen), 20);
        step("14-loading-later", () -> {}, always, 100);
        step("15-in-game", () -> {}, () -> mc.world != null && mc.player != null && mc.currentScreen == null, 200);
        step("16-pause", () -> mc.openPauseMenu(false), always, 30);
        step("17-stats", () -> mc.setScreen(new StatsScreen(mc.currentScreen, mc.player.getStatHandler())), always, 60);
        step("18-settings-in-game", () -> mc.setScreen(new SettingsScreen(null, SettingsScreen.VIDEO)), always, 30);
        step("19-vanilla-options-in-game", () -> {
            BlockyardUi.vanillaOptions = true;
            mc.setScreen(new OptionsScreen(null, mc.options));
        }, always, 30);
        step(null, mc::scheduleStop, always, 0);
    }

    /** Presses the vanilla button whose message is the given translation key's text. */
    private static void press(Screen screen, String key) {
        if (screen == null) return;
        String label = net.minecraft.text.Text.translatable(key).getString();
        for (Element e : screen.children()) {
            if (e instanceof ButtonWidget b && b.getMessage().getString().equals(label)) {
                b.onPress();
                return;
            }
        }
    }

    /** Called at the end of every client tick. */
    public static void tick(MinecraftClient mc) {
        if (!ENABLED) return;
        if (STEPS.isEmpty()) build(mc);
        if (++total > 20 * 60 * 12) {
            mc.scheduleStop();
            return;
        }
        if (index < 0) {
            index = 0;
            wait = -1;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        if (wait == -1) {
            // Waiting for the step's condition before its action.
            try {
                s.action().run();
            } catch (Throwable t) {
                System.err.println("[Blockyard selftest] step " + s.name() + " failed: " + t);
            }
            wait = -2;
            return;
        }
        if (wait == -2) {
            if (!s.ready().getAsBoolean()) return;
            wait = s.settle();
            return;
        }
        if (wait-- > 0) return;
        if (s.name() != null) {
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, s.name() + ".png", mc.getFramebuffer(), msg -> {});
            System.out.println("[Blockyard selftest] saved " + s.name() + " (" + (mc.currentScreen == null ? "no screen" : mc.currentScreen.getClass().getName()) + ")");
        }
        index++;
        wait = -1;
    }
}
