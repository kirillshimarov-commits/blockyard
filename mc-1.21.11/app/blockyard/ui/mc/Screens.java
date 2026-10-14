package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.LoadingView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;

/** Which vanilla screen becomes which Blockyard screen, and the loading screens' look. */
public final class Screens {
    private static java.lang.ref.WeakReference<Screen> allowedOptions;

    private Screens() {}

    public static Screen replace(Minecraft client, Screen screen) {
        if (screen instanceof TitleScreen) {
            if (BlockyardUi.vanillaOnce) {
                BlockyardUi.vanillaOnce = false;
                return screen;
            }
            BlockyardUi.loadingTitle = null;
            BlockyardUi.loadingIcon = null;
            BlockyardUi.loadingKey = "loading";
            return new HomeScreen();
        }
        if (screen instanceof JoinMultiplayerScreen) return new HomeScreen(HomeView.MULTI);
        if (screen instanceof OptionsScreen) {
            // The vanilla options asked for from our settings stay vanilla, also when their
            // sub-screens (Sodium's video settings and others) go back to them.
            if (BlockyardUi.vanillaOptions) {
                BlockyardUi.vanillaOptions = false;
                allowedOptions = new java.lang.ref.WeakReference<>(screen);
                return screen;
            }
            if (allowedOptions != null && allowedOptions.get() == screen) return screen;
            return new SettingsScreen(client.screen, SettingsScreen.GENERAL);
        }
        if (screen instanceof PauseScreen menu && menu.showsPauseMenu()) return new app.blockyard.ui.mc.PauseScreen();
        if (screen == null && BlockyardUi.inviteOnJoin && client.level != null && client.isLocalServer()) {
            BlockyardUi.inviteOnJoin = false;
            app.blockyard.ui.mc.PauseScreen.openToLan(client);
            return new app.blockyard.ui.mc.PauseScreen();
        }
        return screen;
    }

    /** Draws a loading screen like the launcher's launch screen; vanilla buttons keep working. */
    public static void loading(Screen screen, GuiGraphics ctx, String status) {
        BlockyardUi.loadConfig();
        Minecraft mc = Minecraft.getInstance();
        McCanvas c = new McCanvas(ctx);
        float sx = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getScreenWidth());
        float sy = mc.getWindow().getHeight() / (float) Math.max(1, mc.getWindow().getScreenHeight());
        float mx = (float) mc.mouseHandler.xpos() * sx / c.ppu(), my = (float) mc.mouseHandler.ypos() * sy / c.ppu();
        Frame f = new Frame(c, mx, my, UiScreen.seconds(), BlockyardUi.accent, false);
        String title = BlockyardUi.loadingTitle;
        if (title == null && mc.getSingleplayerServer() != null) title = mc.getSingleplayerServer().getWorldData().getLevelName();
        if (title == null) title = "Minecraft";
        LoadingView.draw(f, title, status, BlockyardUi.loadingIcon, BlockyardUi.loadingKey, -1);
        float u = c.guiScale() / c.ppu();
        for (GuiEventListener e : screen.children()) {
            if (e instanceof Button b && b.visible) {
                LoadingView.button(f, b.getMessage().getString(), b.getX() * u, b.getY() * u, b.getWidth() * u, b.getHeight() * u);
            }
        }
    }
}
