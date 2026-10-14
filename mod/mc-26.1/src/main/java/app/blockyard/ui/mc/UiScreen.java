package app.blockyard.ui.mc;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.core.Frame;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** A screen drawn entirely by a core view on the {@link McCanvas}; clicks go to the view's regions. */
public abstract class UiScreen extends Screen {
    private static final long START = System.nanoTime();

    private List<Frame.Hit> hits = List.of();
    private Frame.Drag drag;
    private float unit = 1;
    /** Drawn behind a dialog: no hover, no clicks. */
    private boolean inert;

    protected UiScreen(Component title) {
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
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Everything is drawn in render().
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        McCanvas c = new McCanvas(ctx);
        unit = c.guiScale() / c.ppu();
        backdrop(ctx, c, delta);
        Minecraft mc = Minecraft.getInstance();
        float sx = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getScreenWidth());
        float sy = mc.getWindow().getHeight() / (float) Math.max(1, mc.getWindow().getScreenHeight());
        float mx = (float) mc.mouseHandler.xpos() * sx / c.ppu(), my = (float) mc.mouseHandler.ypos() * sy / c.ppu();
        if (inert) mx = my = -1000;
        Frame f = new Frame(c, mx, my, seconds(), BlockyardUi.accent, modal());
        draw(f, delta);
        hits = f.hits;
    }

    /** Renders this screen as the backdrop of another one. */
    public void renderInert(GuiGraphicsExtractor ctx, float delta) {
        inert = true;
        extractRenderState(ctx, -1000, -1000, delta);
        inert = false;
    }

    /** What is behind the view (space by default is drawn by the views themselves). */
    protected void backdrop(GuiGraphicsExtractor ctx, McCanvas c, float delta) {}

    protected abstract void draw(Frame f, float delta);

    /** While true only popup regions react; a click elsewhere calls {@link #closePopup()}. */
    protected boolean modal() {
        return false;
    }

    protected void closePopup() {}

    protected void click() {
        if (minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Converts GUI coordinates to canvas units. */
    protected float units(double gui) {
        return (float) gui * unit;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x(), mouseY = click.y();
        int button = click.button();
        if (button != 0) return super.mouseClicked(click, doubled);
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
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        double mouseX = click.x();
        if (drag != null) {
            drag.to(units(mouseX));
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (drag != null) {
            drag = null;
            released();
            return true;
        }
        return super.mouseReleased(click);
    }

    /** A slider drag ended. */
    protected void released() {}
}
