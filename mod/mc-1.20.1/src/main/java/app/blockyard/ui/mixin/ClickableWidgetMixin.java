package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TabButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Buttons, cycling buttons, sliders and tabs of restyled screens. In 1.20.1 they all draw through
 * ClickableWidget.renderButton (later versions have a renderWidget per class).
 */
@Mixin(ClickableWidget.class)
public abstract class ClickableWidgetMixin {
    @Shadow
    public boolean active;

    @Shadow
    protected float alpha;

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        ClickableWidget w = (ClickableWidget) (Object) this;
        boolean hot = w.isHovered() || w.isFocused();
        String message = w.getMessage().getString();
        if (w instanceof TabButtonWidget tab) {
            Restyle.tab(context, w.getX(), w.getY(), w.getWidth(), w.getHeight(), message, tab.isCurrentTab(), hot, active);
        } else if (w instanceof SliderWidget) {
            double value = ((SliderWidgetAccessor) w).blockyard$value();
            Restyle.slider(context, w.getX(), w.getY(), w.getWidth(), w.getHeight(), message, value, active, hot, alpha);
        } else if (w.getClass() == ButtonWidget.class || w instanceof CyclingButtonWidget<?>) {
            Restyle.button(context, w.getX(), w.getY(), w.getWidth(), w.getHeight(), message, active, w.isHovered(), w.isFocused(), alpha);
        } else {
            return;
        }
        ci.cancel();
    }
}
