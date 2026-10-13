package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.List;

/** A screen drawn entirely by a core view on the {@link McCanvas}; clicks go to the view's regions. */
public abstract class UiScreen extends Screen {
    private static final long START = System.nanoTime();

    private List<Frame.Hit> hits = List.of();
    private Frame.Drag drag;
    private float unit = 1;
    /** Drawn behind a dialog: no hover, no clicks. */
    private boolean inert;

    protected UiScreen(Text title) {
        super(title);
    }

    public static float seconds() {
        return (System.nanoTime() - START) / 1e9f;
    }

    @Override
    protected void init() {
        BlockyardUi.loadConfig();
    }

    @Override
    public void renderBackground(DrawContext context) {
        // Everything is drawn in render().
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        McCanvas c = new McCanvas(ctx);
        unit = c.guiScale() / c.ppu();
        backdrop(ctx, c, delta);
        MinecraftClient mc = MinecraftClient.getInstance();
        float sx = mc.getWindow().getFramebufferWidth() / (float) Math.max(1, mc.getWindow().getWidth());
        float sy = mc.getWindow().getFramebufferHeight() / (float) Math.max(1, mc.getWindow().getHeight());
        float mx = (float) mc.mouse.getX() * sx / c.ppu(), my = (float) mc.mouse.getY() * sy / c.ppu();
        if (inert) mx = my = -1000;
        Frame f = new Frame(c, mx, my, seconds(), BlockyardUi.accent, modal());
        draw(f, delta);
        hits = f.hits;
    }

    /** Renders this screen as the backdrop of another one. */
    public void renderInert(DrawContext ctx, float delta) {
        inert = true;
        render(ctx, -1000, -1000, delta);
        inert = false;
    }

    /** What is behind the view (space by default is drawn by the views themselves). */
    protected void backdrop(DrawContext ctx, McCanvas c, float delta) {}

    protected abstract void draw(Frame f, float delta);

    /** While true only popup regions react; a click elsewhere calls {@link #closePopup()}. */
    protected boolean modal() {
        return false;
    }

    protected void closePopup() {}

    protected void click() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Converts GUI coordinates to canvas units. */
    protected float units(double gui) {
        return (float) gui * unit;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        float ux = units(mouseX), uy = units(mouseY);
        Frame.Hit hit = Frame.find(hits, ux, uy, modal());
        if (hit != null) {
            if (hit.drag() != null) {
                drag = hit.drag();
                drag.to(ux);
            } else {
                click();
                hit.action().run();
            }
            return true;
        }
        if (modal()) {
            closePopup();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (drag != null) {
            drag.to(units(mouseX));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (drag != null) {
            drag = null;
            released();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** A slider drag ended. */
    protected void released() {}
}
