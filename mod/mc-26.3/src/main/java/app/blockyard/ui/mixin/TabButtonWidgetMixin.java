package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tabs (create world and others) as the launcher's tab pills; from 26.2 drawn by MenuTabBar.MenuTabButton. */
@Mixin(MenuTabBar.MenuTabButton.class)
public abstract class TabButtonWidgetMixin extends AbstractWidget {
    public TabButtonWidgetMixin(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        TabButton self = (TabButton) (Object) this;
        Restyle.tab(context, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), self.isSelected(), isHovered() || isFocused(), this.active);
        ci.cancel();
    }
}
