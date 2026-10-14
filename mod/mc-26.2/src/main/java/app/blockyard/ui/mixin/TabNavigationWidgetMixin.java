package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The strip behind the tabs as one glass bar. From 26.2 the menus' tab bar is MenuTabBar, a container widget. */
@Mixin(MenuTabBar.class)
public abstract class TabNavigationWidgetMixin {
    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), require = 0)
    private void blockyard$bar(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (GuiEventListener e : ((TabNavigationBar) (Object) this).children()) {
            if (!(e instanceof TabButton b)) continue;
            x0 = Math.min(x0, b.getX());
            y0 = Math.min(y0, b.getY());
            x1 = Math.max(x1, b.getX() + b.getWidth());
            y1 = Math.max(y1, b.getY() + b.getHeight());
        }
        if (x0 < x1) Restyle.tabBar(context, x0 - 2, y0 + 1, x1 + 2, y1 - 1);
    }
}
