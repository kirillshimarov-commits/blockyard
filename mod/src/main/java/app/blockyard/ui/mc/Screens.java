package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.LoadingView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Which vanilla screen becomes which Blockyard screen, and the loading screens' look. */
public final class Screens {
    private static java.lang.ref.WeakReference<Screen> allowedOptions;

    private Screens() {}

    public static Screen replace(MinecraftClient client, Screen screen) {
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
        if (screen instanceof MultiplayerScreen) return new HomeScreen(HomeView.MULTI);
        if (screen instanceof OptionsScreen) {
            // The vanilla options asked for from our settings stay vanilla, also when their
            // sub-screens (Sodium's video settings and others) go back to them.
            if (BlockyardUi.vanillaOptions) {
                BlockyardUi.vanillaOptions = false;
                allowedOptions = new java.lang.ref.WeakReference<>(screen);
                return screen;
            }
            if (allowedOptions != null && allowedOptions.get() == screen) return screen;
            return new SettingsScreen(client.currentScreen, SettingsScreen.GENERAL);
        }
        if (screen instanceof GameMenuScreen menu && menu.shouldShowMenu()) return new PauseScreen();
        if (screen == null && BlockyardUi.inviteOnJoin && client.world != null && client.isInSingleplayer()) {
            BlockyardUi.inviteOnJoin = false;
            PauseScreen.openToLan(client);
            return new PauseScreen();
        }
        return screen;
    }

    /** Draws a loading screen like the launcher's launch screen; vanilla buttons keep working. */
    public static void loading(Screen screen, DrawContext ctx, String status) {
        BlockyardUi.loadConfig();
        MinecraftClient mc = MinecraftClient.getInstance();
        McCanvas c = new McCanvas(ctx);
        float sx = mc.getWindow().getFramebufferWidth() / (float) Math.max(1, mc.getWindow().getWidth());
        float sy = mc.getWindow().getFramebufferHeight() / (float) Math.max(1, mc.getWindow().getHeight());
        float mx = (float) mc.mouse.getX() * sx / c.ppu(), my = (float) mc.mouse.getY() * sy / c.ppu();
        Frame f = new Frame(c, mx, my, UiScreen.seconds(), BlockyardUi.accent, false);
        String title = BlockyardUi.loadingTitle != null ? BlockyardUi.loadingTitle : "Minecraft";
        LoadingView.draw(f, title, status, BlockyardUi.loadingIcon, BlockyardUi.loadingKey, -1);
        float u = c.guiScale() / c.ppu();
        for (Element e : screen.children()) {
            if (e instanceof ButtonWidget b && b.visible) {
                LoadingView.button(f, b.getMessage().getString(), b.getX() * u, b.getY() * u, b.getWidth() * u, b.getHeight() * u);
            }
        }
    }
}
