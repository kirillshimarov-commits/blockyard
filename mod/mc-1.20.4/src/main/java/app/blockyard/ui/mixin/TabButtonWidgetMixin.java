package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TabButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tabs (create world and others) as the launcher's tab pills. */
@Mixin(TabButtonWidget.class)
public abstract class TabButtonWidgetMixin extends ClickableWidget {
    public TabButtonWidgetMixin(int x, int y, int width, int height, Text message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        TabButtonWidget self = (TabButtonWidget) (Object) this;
        Restyle.tab(context, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), self.isCurrentTab(), isHovered() || isFocused(), this.active);
        ci.cancel();
    }
}
