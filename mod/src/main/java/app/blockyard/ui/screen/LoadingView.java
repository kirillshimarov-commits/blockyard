package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * The look of every loading screen (connecting, generating the world, saving): the same as the
 * launcher's launch screen. Vanilla buttons keep working; they are only drawn in our style.
 */
public final class LoadingView {
    private static final SpaceBackground BACKGROUND = new SpaceBackground();
    private static final float CANVAS_H = 270f;

    private LoadingView() {}

    public static void render(Screen screen, DrawContext ctx, int mouseX, int mouseY, String status) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        int accent = BlockyardUi.accent;
        BACKGROUND.render(ctx, screen.width, screen.height, accent, 1f);

        float k = screen.height / CANVAS_H, vw = screen.width / k;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        float t = System.currentTimeMillis() / 1000f;
        float cy = CANVAS_H / 2 - 20;
        float size = 42, ix = vw / 2 - size / 2, iy = cy - size + (float) Math.sin(t * 1.6) * 1.5f;
        Ui.light(ctx, Ui.tex("glow"), ix - size * 0.7f, iy - size * 0.6f, size * 2.4f, size * 2.4f, Ui.argb(0.5f, accent));
        Identifier icon = BlockyardUi.loadingIcon;
        String title = BlockyardUi.loadingTitle != null ? BlockyardUi.loadingTitle : "Minecraft";
        if (icon != null) {
            Ui.round(ctx, ix, iy, size, size, 9, 0xFF16141F);
            Ui.image(ctx, icon, ix + 2, iy + 2, size - 4, size - 4, 0xFFFFFFFF);
        } else {
            Ui.round(ctx, ix, iy, size, size, 9, Ui.argb(1, Ui.mix(accent, 0xFFFFFF, 0.15f)), Ui.argb(1, Ui.mix(accent, 0x000000, 0.35f)));
            m.push();
            float s = size / 32f;
            m.translate(vw / 2, iy + size / 2 - 8.5f * s, 0);
            m.scale(s, s, 1);
            Ui.centered(ctx, tr, title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT), Ui.TITLE, 0, 0, 0xFFFFFFFF);
            m.pop();
        }
        Ui.centered(ctx, tr, Ui.fit(tr, title, Ui.HEAD, vw * 0.8f), Ui.HEAD, vw / 2, cy + 10, 0xFFFFFFFF);
        Ui.centered(ctx, tr, Ui.fit(tr, status, Ui.BODY, vw * 0.8f), Ui.BODY, vw / 2, cy + 22, Ui.argb(0.62f, 0xFFFFFF));

        float bw = 95, bx = vw / 2 - bw / 2, by = cy + 31;
        Ui.round(ctx, bx, by, bw, 1.5f, 0.75f, 0x1AFFFFFF);
        float seg = bw * 0.35f, pos = (t * 0.6f % 1.35f) - 0.35f;
        float sx = Math.max(bx, bx + pos * bw), ex = Math.min(bx + bw, bx + pos * bw + seg);
        if (ex > sx) Ui.round(ctx, sx, by, ex - sx, 1.5f, 0.75f, 0xFFFFFFFF);
        m.pop();

        // The vanilla buttons (Cancel, Back): drawn as our pills at their own places.
        for (Element e : screen.children()) {
            if (e instanceof ButtonWidget b && b.visible) {
                boolean hov = b.isMouseOver(mouseX, mouseY);
                float r = b.getHeight() / 2f;
                Ui.round(ctx, b.getX(), b.getY(), b.getWidth(), b.getHeight(), r, hov ? 0x33FFFFFF : 0x1FFFFFFF);
                String label = b.getMessage().getString();
                float lw = Ui.width(tr, label, Ui.BODY) * k;
                m.push();
                m.translate(b.getX() + b.getWidth() / 2f - lw / 2, b.getY() + b.getHeight() / 2f - 2.2f * k, 0);
                m.scale(k, k, 1);
                Ui.text(ctx, tr, label, Ui.BODY, 0, 0, 0xFFFFFFFF);
                m.pop();
            }
        }
    }
}
