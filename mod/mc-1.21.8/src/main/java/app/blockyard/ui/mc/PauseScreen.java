package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import app.blockyard.ui.core.HomeView;
import app.blockyard.ui.core.PauseView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.NetworkUtils;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** The pause menu: {@link PauseView} over the blurred game, with the friend invite code when hosting. */
public class PauseScreen extends UiScreen implements PauseView.Model {
    private final long openedAt = System.currentTimeMillis();
    private String inviteCode = null;
    private long inviteCheckedAt = 0;
    private boolean opening = false;
    private long copiedAt = 0;

    public PauseScreen() {
        super(Text.literal("Пауза"));
    }

    @Override
    protected void backdrop(DrawContext ctx, McCanvas c, float delta) {
        applyBlur(ctx);
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
            Path file = client.runDirectory.toPath().resolve("blockyard").resolve("invite.txt");
            inviteCode = Files.isRegularFile(file) ? Files.readString(file).trim() : null;
            if (inviteCode != null && inviteCode.isEmpty()) inviteCode = null;
        } catch (Exception e) {
            inviteCode = null;
        }
    }

    /** Opens the singleplayer world to LAN; the launcher turns that into an invite code. */
    static boolean openToLan(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null || client.interactionManager == null) return false;
        if (server.isRemote()) return true;
        return server.openToLan(client.interactionManager.getCurrentGameMode(), false, NetworkUtils.findLocalPort());
    }

    private boolean hosting() {
        return client.getServer() != null && client.getServer().isRemote();
    }

    public String subtitle() {
        String world = BlockyardUi.loadingTitle;
        if (world == null) world = singleplayer() ? "Одиночная игра" : "Сетевая игра";
        if (client.world != null) world += " · день " + (client.world.getTimeOfDay() / 24000L + 1);
        return world;
    }

    public boolean singleplayer() {
        return client.isInSingleplayer();
    }

    public String invite() {
        if (!singleplayer() || !(hosting() || opening)) return null;
        return inviteCode == null ? "" : inviteCode;
    }

    public boolean copiedRecently() {
        return System.currentTimeMillis() - copiedAt < 1500;
    }

    public void resume() {
        client.setScreen(null);
    }

    public void advancements() {
        if (client.player != null) client.setScreen(new AdvancementsScreen(client.player.networkHandler.getAdvancementHandler(), this));
    }

    public void stats() {
        if (client.player != null) client.setScreen(new StatsScreen(this, client.player.getStatHandler()));
    }

    public void settings() {
        client.setScreen(new SettingsScreen(this, SettingsScreen.GENERAL));
    }

    public void openToFriend() {
        if (!hosting() && !opening) {
            opening = true;
            openToLan(client);
        }
    }

    public void copy() {
        if (inviteCode == null) return;
        client.keyboard.setClipboard(inviteCode);
        copiedAt = System.currentTimeMillis();
    }

    public void quit() {
        boolean single = client.isInSingleplayer();
        Text quitting = Text.translatable("multiplayer.status.quitting");
        if (client.world != null) client.world.disconnect(quitting);
        client.disconnect(new MessageScreen(single ? Text.translatable("menu.savingLevel") : quitting), false);
        client.setScreen(new HomeScreen(single ? HomeView.SINGLE : HomeView.MULTI));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            client.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
