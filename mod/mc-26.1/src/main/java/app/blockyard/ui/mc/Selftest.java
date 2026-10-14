package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.HomeView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

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

    private static void build(Minecraft mc) {
        BooleanSupplier always = () -> true;
        step(null, () -> {}, () -> mc.screen instanceof HomeScreen && mc.getOverlay() == null, 60);
        step("01-home", () -> home = (HomeScreen) mc.screen, always, 30);
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
        step("06-settings-video", () -> ((SettingsScreen) mc.screen).section(SettingsScreen.VIDEO), always, 30);
        step("07-settings-sound", () -> ((SettingsScreen) mc.screen).section(SettingsScreen.SOUND), always, 30);
        step("08-settings-packs", () -> ((SettingsScreen) mc.screen).section(SettingsScreen.PACKS), always, 30);
        step("09-vanilla-options", () -> {
            BlockyardUi.vanillaOptions = true;
            mc.setScreen(new OptionsScreen(home, mc.options, false));
        }, always, 30);
        step("10-vanilla-video", () -> mc.setScreen(new VideoSettingsScreen(mc.screen, mc, mc.options)), always, 30);
        step("11-vanilla-keybinds", () -> mc.setScreen(new KeyBindsScreen(home, mc.options)), always, 30);
        step("12-create-world", () -> CreateWorldScreen.openFresh(mc, () -> mc.setScreen(home)), () -> mc.screen instanceof CreateWorldScreen, 40);
        step("13-loading", () -> press(mc.screen, "selectWorld.create"), () -> !(mc.screen instanceof CreateWorldScreen), 20);
        step("14-loading-later", () -> {}, always, 100);
        step("15-in-game", () -> {}, () -> mc.level != null && mc.player != null && mc.screen == null, 200);
        step("16-pause", () -> mc.pauseGame(false), always, 30);
        step("17-stats", () -> mc.setScreen(new StatsScreen(mc.screen, mc.player.getStats())), always, 60);
        step("18-settings-in-game", () -> mc.setScreen(new SettingsScreen(null, SettingsScreen.VIDEO)), always, 30);
        step("19-vanilla-options-in-game", () -> {
            BlockyardUi.vanillaOptions = true;
            mc.setScreen(new OptionsScreen(null, mc.options, false));
        }, always, 30);
        step(null, mc::stop, always, 0);
    }

    /** Presses the vanilla button whose message is the given translation key's text. */
    private static void press(Screen screen, String key) {
        if (screen == null) return;
        String label = net.minecraft.network.chat.Component.translatable(key).getString();
        for (GuiEventListener e : screen.children()) {
            if (e instanceof Button b && b.getMessage().getString().equals(label)) {
                b.onPress(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
                return;
            }
        }
    }

    /** Called at the end of every client tick. */
    public static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (STEPS.isEmpty()) build(mc);
        if (++total > 20 * 60 * 12) {
            mc.stop();
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
            Screenshot.grab(mc.gameDirectory, s.name() + ".png", mc.getMainRenderTarget(), 1, msg -> {});
            System.out.println("[Blockyard selftest] saved " + s.name() + " (" + (mc.screen == null ? "no screen" : mc.screen.getClass().getName()) + ")");
        }
        index++;
        wait = -1;
    }
}
