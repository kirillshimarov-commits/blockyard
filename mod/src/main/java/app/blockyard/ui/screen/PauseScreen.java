package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.util.NetworkUtils;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** The pause menu: a glass panel over the blurred game, with the friend invite code when hosting. */
public class PauseScreen extends BlockyardScreen {
    private long openedAt = System.currentTimeMillis();
    private String invite = null;
    private long inviteCheckedAt = 0;
    private boolean opening = false;
    private long copiedAt = 0;

    public PauseScreen() {
        super(Text.literal("Пауза"));
    }

    @Override
    protected void drawBackdrop(DrawContext ctx, float delta) {
        applyBlur(delta);
        ctx.fill(0, 0, width, height, 0x73060510);
    }

    private Path inviteFile() {
        return client.runDirectory.toPath().resolve("blockyard").resolve("invite.txt");
    }

    private void refreshInvite() {
        long now = System.currentTimeMillis();
        if (now - inviteCheckedAt < 700) return;
        inviteCheckedAt = now;
        try {
            Path f = inviteFile();
            invite = Files.isRegularFile(f) ? Files.readString(f).trim() : null;
            if (invite != null && invite.isEmpty()) invite = null;
        } catch (Exception e) {
            invite = null;
        }
    }

    /** Opens the singleplayer world to LAN; the launcher turns that into an invite code. */
    static boolean openToLan(net.minecraft.client.MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null || client.interactionManager == null) return false;
        if (server.isRemote()) return true;
        return server.openToLan(client.interactionManager.getCurrentGameMode(), false, NetworkUtils.findLocalPort());
    }

    @Override
    protected void draw(DrawContext ctx, float delta) {
        float a = ease((System.currentTimeMillis() - openedAt) / 220f);
        boolean single = client.isInSingleplayer();
        boolean hosting = single && client.getServer() != null && client.getServer().isRemote();
        if (single) refreshInvite();

        float w = 140, x = (vw - w) / 2;
        float rows = 4 + (single ? 1 : 0) + (invite != null ? 1.6f : 0);
        float h = 30 + rows * 16.25f;
        float y = (CANVAS_H - h) / 2 + (1 - a) * 6;
        Ui.shadow(ctx, x, y + 8, w, h, 26, 0.75f * a);
        Ui.round(ctx, x, y, w, h, 7.5f, Ui.argb(0.9f * a, 0x2A2838), Ui.argb(0.92f * a, 0x1C1A28));

        Ui.text(ctx, textRenderer, "Пауза", Ui.HEAD, x + 8.5f, y + 8.5f, Ui.argb(a, WHITE));
        String world = BlockyardUi.loadingTitle != null ? BlockyardUi.loadingTitle : single ? "Одиночная игра" : "Сетевая игра";
        Ui.text(ctx, textRenderer, Ui.fit(textRenderer, world, Ui.BODY, w - 17), Ui.BODY, x + 8.5f, y + 18.5f, Ui.argb(0.55f * a, WHITE));

        float bx = x + 8.5f, bw = w - 17, bh = 13.5f, by = y + 30, gap = 2.75f;
        float half = (bw - gap) / 2;
        wideButton(ctx, "Вернуться к игре", bx, by, bw, bh, 1, a, () -> client.setScreen(null));
        by += bh + gap;
        wideButton(ctx, "Достижения", bx, by, half, bh, 0, a, () -> {
            if (client.player != null) client.setScreen(new AdvancementsScreen(client.player.networkHandler.getAdvancementHandler(), this));
        });
        wideButton(ctx, "Статистика", bx + half + gap, by, half, bh, 0, a, () -> {
            if (client.player != null) client.setScreen(new StatsScreen(this, client.player.getStatHandler()));
        });
        by += bh + gap;
        if (single) {
            wideButton(ctx, "Настройки", bx, by, half, bh, 0, a, () -> client.setScreen(new SettingsScreen(this, SettingsScreen.GENERAL)));
            String inviteLabel = hosting ? (invite == null ? "Создаём код…" : "Мир открыт") : "Пригласить друга";
            wideButton(ctx, inviteLabel, bx + half + gap, by, half, bh, 0, a, () -> {
                if (!hosting && !opening) {
                    opening = true;
                    openToLan(client);
                }
            });
        } else {
            wideButton(ctx, "Настройки", bx, by, bw, bh, 0, a, () -> client.setScreen(new SettingsScreen(this, SettingsScreen.GENERAL)));
        }
        by += bh + gap;

        if (invite != null) {
            float ih = 22;
            Ui.round(ctx, bx, by, bw, ih, 5, Ui.argb(0.08f * a, WHITE));
            Ui.text(ctx, textRenderer, "Код для друга (вставить в Blockyard → «Подключиться к другу»)", Ui.SMALL, bx + 4.5f, by + 3.5f, Ui.argb(0.6f * a, WHITE));
            Ui.text(ctx, textRenderer, Ui.fit(textRenderer, invite, Ui.BOLD, bw - 42), Ui.BOLD, bx + 4.5f, by + 11.5f, Ui.argb(a, WHITE));
            boolean copied = System.currentTimeMillis() - copiedAt < 1500;
            String label = copied ? "Скопировано" : "Копировать";
            float cw = Ui.width(textRenderer, label, Ui.BOLD) + 10;
            float cx = bx + bw - 4 - cw;
            boolean hov = hover(cx, by + 9, cw, 9.5f);
            Ui.round(ctx, cx, by + 9, cw, 9.5f, 4.75f, Ui.argb(a, copied ? 0x3EE08F : hov ? 0xFFFFFF : 0xEDEBF5));
            Ui.text(ctx, textRenderer, label, Ui.BOLD, cx + 5, by + 11.5f, Ui.argb(a, INK));
            final String code = invite;
            region(cx, by + 9, cw, 9.5f, () -> {
                click();
                client.keyboard.setClipboard(code);
                copiedAt = System.currentTimeMillis();
            });
            by += ih + gap;
        }

        wideButton(ctx, single ? "Сохранить и выйти в меню" : "Отключиться", bx, by, bw, bh, 2, a, this::quit);
        Ui.centered(ctx, textRenderer, "Esc — продолжить", Ui.SMALL, vw / 2, CANVAS_H - 12, Ui.argb(0.4f * a, WHITE));
    }

    private void quit() {
        boolean single = client.isInSingleplayer();
        if (client.world != null) client.world.disconnect();
        if (single) client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
        else client.disconnect();
        client.setScreen(new HomeScreen(single ? HomeScreen.TAB_SINGLE : HomeScreen.TAB_MULTI));
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
