package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.PauseView;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;

/** The pause menu: {@link PauseView} over the blurred game, with the friend invite code when hosting. */
public class PauseScreen extends UiScreen implements PauseView.Model {
    private final long openedAt = System.currentTimeMillis();
    private String inviteCode = null;
    private long inviteCheckedAt = 0;
    private boolean opening = false;
    private long copiedAt = 0;

    public PauseScreen() {
        super(Component.literal("Пауза"));
    }

    @Override
    protected void backdrop(GuiGraphics ctx, McCanvas c, float delta) {
        renderBlurredBackground(ctx);
        c.fillScreen(0x73060510);
    }

    @Override
    protected void draw(Frame f, float delta) {
        if (singleplayer()) refreshInvite();
        PauseView.draw(f, this, HomeView.ease((System.currentTimeMillis() - openedAt) / 220f));
    }

    private void refreshInvite() {
        long now = System.currentTimeMillis();
        if (now - inviteCheckedAt < 700) return;
        inviteCheckedAt = now;
        try {
            Path file = minecraft.gameDirectory.toPath().resolve("blockyard").resolve("invite.txt");
            inviteCode = Files.isRegularFile(file) ? Files.readString(file).trim() : null;
            if (inviteCode != null && inviteCode.isEmpty()) inviteCode = null;
        } catch (Exception e) {
            inviteCode = null;
        }
    }

    /** Opens the singleplayer world to LAN; the launcher turns that into an invite code. */
    static boolean openToLan(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.gameMode == null) return false;
        if (server.isPublished()) return true;
        return server.publishServer(client.gameMode.getPlayerMode(), false, HttpUtil.getAvailablePort());
    }

    private boolean hosting() {
        return minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isPublished();
    }

    public String subtitle() {
        String world = BlockyardUi.loadingTitle;
        if (world == null) world = singleplayer() ? "Одиночная игра" : "Сетевая игра";
        if (minecraft.level != null) world += " · день " + (minecraft.level.getDayTime() / 24000L + 1);
        return world;
    }

    public boolean singleplayer() {
        return minecraft.isLocalServer();
    }

    public String invite() {
        if (!singleplayer() || !(hosting() || opening)) return null;
        return inviteCode == null ? "" : inviteCode;
    }

    public boolean copiedRecently() {
        return System.currentTimeMillis() - copiedAt < 1500;
    }

    public void resume() {
        minecraft.setScreen(null);
    }

    public void advancements() {
        if (minecraft.player != null) minecraft.setScreen(new AdvancementsScreen(minecraft.player.connection.getAdvancements(), this));
    }

    public void stats() {
        if (minecraft.player != null) minecraft.setScreen(new StatsScreen(this, minecraft.player.getStats()));
    }

    public void settings() {
        minecraft.setScreen(new SettingsScreen(this, SettingsScreen.GENERAL));
    }

    public void openToFriend() {
        if (!hosting() && !opening) {
            opening = true;
            openToLan(minecraft);
        }
    }

    public void copy() {
        if (inviteCode == null) return;
        minecraft.keyboardHandler.setClipboard(inviteCode);
        copiedAt = System.currentTimeMillis();
    }

    public void quit() {
        boolean single = minecraft.isLocalServer();
        Component quitting = Component.translatable("multiplayer.status.quitting");
        if (minecraft.level != null) minecraft.level.disconnect(quitting);
        minecraft.disconnect(new GenericMessageScreen(single ? Component.translatable("menu.savingLevel") : quitting), false);
        minecraft.setScreen(new HomeScreen(single ? HomeView.SINGLE : HomeView.MULTI));
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int keyCode = input.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(input);
    }
}
