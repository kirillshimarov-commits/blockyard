package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla sliders: name and value on a glass pill, the track with its knob along the bottom. */
@Mixin(SliderWidget.class)
public abstract class SliderWidgetMixin extends ClickableWidget {
    @Shadow
    protected double value;

    public SliderWidgetMixin(int x, int y, int width, int height, Text message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        Restyle.slider(context, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), value, this.active, isHovered() || isFocused(), this.alpha);
        ci.cancel();
    }
}
