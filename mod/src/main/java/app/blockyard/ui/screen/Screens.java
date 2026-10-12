package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;

/** Which vanilla screen becomes which Blockyard screen. */
public final class Screens {
    private Screens() {}

    public static Screen replace(MinecraftClient client, Screen screen) {
        if (screen instanceof TitleScreen) {
            if (BlockyardUi.vanillaOnce) {
                BlockyardUi.vanillaOnce = false;
                return screen;
            }
            BlockyardUi.loadingTitle = null;
            BlockyardUi.loadingIcon = null;
            return new HomeScreen();
        }
        if (screen instanceof MultiplayerScreen) return new HomeScreen(HomeScreen.TAB_MULTI);
        if (screen instanceof OptionsScreen) {
            if (BlockyardUi.vanillaOptions) {
                BlockyardUi.vanillaOptions = false;
                return screen;
            }
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
}
