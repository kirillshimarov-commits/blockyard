package app.blockyard.ui.screen;

import app.blockyard.ui.BlockyardUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared base of the Blockyard screens: a virtual canvas 270 units tall scaled to the window,
 * clickable regions in canvas units, the space background and the launcher-style top bar.
 */
abstract class BlockyardScreen extends Screen {
    static final float CANVAS_H = 270f;
    static final int WHITE = 0xFFFFFF, INK = 0x0B0A12, MUTED = 0xA39FB8;
    static final String[] TABS = {"Одиночная игра", "Сетевая игра", "Ресурспаки", "Моды"};

    private static final SpaceBackground BACKGROUND = new SpaceBackground();

    protected final List<Region> regions = new ArrayList<>();
    protected float k = 1, vw = 480;
    /** Mouse position in canvas units, updated every frame. */
    protected float mx, my;
    /** While a popup is open, only its regions react. */
    protected boolean modal = false;

    protected record Region(float x, float y, float w, float h, Runnable action, boolean popup) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    protected BlockyardScreen(Text title) {
        super(title);
    }

    @Override
    protected void init() {
        BlockyardUi.loadConfig();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Drawn in render().
    }

    /** Draws the background, sets up the canvas and calls {@link #draw}. */
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        regions.clear();
        drawBackdrop(ctx, delta);
        k = height / CANVAS_H;
        vw = width / k;
        mx = mouseX / k;
        my = mouseY / k;
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        draw(ctx, delta);
        m.pop();
    }

    /** What is behind the canvas: space by default. */
    protected void drawBackdrop(DrawContext ctx, float delta) {
        BACKGROUND.render(ctx, width, height, BlockyardUi.accent, 1f);
    }

    protected abstract void draw(DrawContext ctx, float delta);

    // ---------------------------------------------------------------- helpers

    protected boolean hover(float x, float y, float w, float h) {
        return !modal && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    protected boolean hoverAny(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    protected void region(float x, float y, float w, float h, Runnable action) {
        regions.add(new Region(x, y, w, h, action, false));
    }

    protected void popupRegion(float x, float y, float w, float h, Runnable action) {
        regions.add(new Region(x, y, w, h, action, true));
    }

    protected void click() {
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** White pill button with an optional accent glow on hover; returns its width. */
    protected float pillButton(DrawContext ctx, String label, float x, float y, float h, boolean primary, float a, Runnable action) {
        float w = Ui.width(textRenderer, label, Ui.BOLD) + 30;
        boolean hov = hover(x, y, w, h);
        if (primary) {
            if (hov) Ui.light(ctx, Ui.tex("glow"), x - 18, y - 16, w + 36, h + 32, Ui.argb(0.5f * a, BlockyardUi.accent));
            Ui.round(ctx, x, y, w, h, h / 2, Ui.argb(a, WHITE));
            Ui.centered(ctx, textRenderer, label, Ui.BOLD, x + w / 2, y + (h - 8) / 2, Ui.argb(a, INK));
        } else {
            Ui.round(ctx, x, y, w, h, h / 2, Ui.argb((hov ? 0.2f : 0.1f) * a, WHITE));
            Ui.centered(ctx, textRenderer, label, Ui.BODY, x + w / 2, y + (h - 8) / 2, Ui.argb(a, WHITE));
        }
        region(x, y, w, h, () -> {
            click();
            action.run();
        });
        return w;
    }

    /** Full-width button inside a panel. */
    protected void wideButton(DrawContext ctx, String label, float x, float y, float w, float h, int style, float a, Runnable action) {
        boolean hov = hover(x, y, w, h);
        switch (style) {
            case 1 -> { // primary
                if (hov) Ui.light(ctx, Ui.tex("glow"), x - 20, y - 16, w + 40, h + 32, Ui.argb(0.45f * a, BlockyardUi.accent));
                Ui.round(ctx, x, y, w, h, h / 2, Ui.argb(a, WHITE));
                Ui.centered(ctx, textRenderer, label, Ui.BOLD, x + w / 2, y + (h - 8) / 2, Ui.argb(a, INK));
            }
            case 2 -> { // danger
                Ui.round(ctx, x, y, w, h, h / 2, Ui.argb((hov ? 0.28f : 0.16f) * a, 0xEF5B5B));
                Ui.centered(ctx, textRenderer, label, Ui.BODY, x + w / 2, y + (h - 8) / 2, Ui.argb(a, 0xFF9A9A));
            }
            default -> {
                Ui.round(ctx, x, y, w, h, h / 2, Ui.argb((hov ? 0.18f : 0.10f) * a, WHITE));
                Ui.centered(ctx, textRenderer, label, Ui.BODY, x + w / 2, y + (h - 8) / 2, Ui.argb(a, WHITE));
            }
        }
        region(x, y, w, h, () -> {
            click();
            action.run();
        });
    }

    /** Glass panel like the launcher's cards. */
    protected void panel(DrawContext ctx, float x, float y, float w, float h, float r, float a) {
        Ui.shadow(ctx, x, y + 6, w, h, 18, 0.35f * a);
        Ui.round(ctx, x, y, w, h, r, Ui.argb(0.075f * a, WHITE));
        Ui.round(ctx, x + r * 0.6f, y, w - r * 1.2f, 0.6f, 0.3f, Ui.argb(0.10f * a, WHITE));
    }

    /**
     * The launcher-style top bar: tab pills on the left (activeTab -1 for none), settings,
     * avatar and clock on the right.
     */
    protected void drawTopBar(DrawContext ctx, int activeTab, boolean settingsActive, float a, TabHandler onTab) {
        float x = 16, y = 11, h = 17;
        float total = 4;
        for (String t : TABS) total += Ui.width(textRenderer, t, Ui.BODY) + 20 + 1;
        Ui.shadow(ctx, x, y + 3, total, h + 4, 8, 0.3f * a);
        Ui.round(ctx, x - 2, y - 2, total, h + 4, (h + 4) / 2, Ui.argb(0.06f * a, WHITE));
        float cx = x;
        for (int i = 0; i < TABS.length; i++) {
            float w = Ui.width(textRenderer, TABS[i], Ui.BODY) + 20;
            boolean active = i == activeTab;
            boolean hov = hover(cx, y, w, h);
            if (active) {
                Ui.light(ctx, Ui.tex("glow"), cx - 6, y - 8, w + 12, h + 16, Ui.argb(0.16f * a, WHITE));
                Ui.round(ctx, cx, y, w, h, h / 2, Ui.argb(a, WHITE));
            } else if (hov) {
                Ui.round(ctx, cx, y, w, h, h / 2, Ui.argb(0.08f * a, WHITE));
            }
            Ui.text(ctx, textRenderer, TABS[i], Ui.BODY, cx + 10, y + 5, Ui.argb(a * (active ? 1 : hov ? 0.95f : 0.72f), active ? INK : WHITE));
            final int target = i;
            region(cx, y, w, h, () -> {
                click();
                onTab.open(target);
            });
            cx += w + 1;
        }

        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        String day = ", " + capitalize(LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru")));
        float tw = Ui.width(textRenderer, time, Ui.BOLD), dw = Ui.width(textRenderer, day, Ui.BODY);
        float boxW = 4 + 17 + 4 + 17 + 8 + tw + dw + 12;
        float bx = vw - 16 - boxW;
        Ui.round(ctx, bx, y - 2, boxW, h + 4, (h + 4) / 2, Ui.argb(0.06f * a, WHITE));
        float gx = bx + 2;
        boolean hovGear = hover(gx, y - 0.5f, 18, 18);
        if (settingsActive || hovGear) Ui.circle(ctx, gx + 9, y + h / 2, 8.5f, Ui.argb((settingsActive ? 0.16f : 0.10f) * a, WHITE));
        Ui.icon(ctx, "gear", gx + 3.5f, y + 3, 11, Ui.argb(a, WHITE));
        region(gx, y - 0.5f, 18, 18, () -> {
            click();
            if (!settingsActive) client.setScreen(new SettingsScreen(this, 0));
        });
        float ax = gx + 18 + 4 + 8;
        Ui.circle(ctx, ax, y + h / 2, 8, Ui.argb(a, Ui.mix(BlockyardUi.accent, WHITE, 0.08f)));
        String name = client.getSession().getUsername();
        Ui.centered(ctx, textRenderer, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), Ui.BOLD, ax, y + 5, Ui.argb(a, WHITE));
        float tx = ax + 8 + 8;
        Ui.text(ctx, textRenderer, time, Ui.BOLD, tx, y + 5, Ui.argb(a, WHITE));
        Ui.text(ctx, textRenderer, day, Ui.BODY, tx + tw, y + 5, Ui.argb(0.6f * a, WHITE));
    }

    interface TabHandler {
        void open(int tab);
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double px = mouseX / k, py = mouseY / k;
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (modal && !r.popup()) continue;
            if (r.contains(px, py)) {
                r.action().run();
                return true;
            }
        }
        if (modal) {
            closePopup();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    protected void closePopup() {
        modal = false;
    }
}
