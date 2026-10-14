package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Plain and cycling buttons of restyled screens: glass pills, "name: value" laid out in two parts. */
@Mixin(AbstractButton.class)
public abstract class PressableWidgetMixin extends AbstractWidget {
    public PressableWidgetMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Object self = this;
        if (!Restyle.active() || !(self.getClass() == Button.Plain.class || self instanceof CycleButton<?>)) return;
        Restyle.button(context, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), this.active, isHovered(), isFocused(), this.alpha);
        ci.cancel();
    }
}
