package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TabButtonWidget;
import net.minecraft.client.gui.widget.TabNavigationWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The tab row (create world and others) sits in one glass bar, like the launcher's tabs. */
@Mixin(TabNavigationWidget.class)
public abstract class TabNavigationWidgetMixin {
    @Shadow
    @Final
    private ImmutableList<TabButtonWidget> tabButtons;

    /** 1.20.1 paints a black strip behind the tabs; the glass bar replaces it. */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"), require = 0)
    private void blockyard$strip(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        if (!Restyle.active()) context.fill(x1, y1, x2, y2, color);
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void blockyard$bar(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active() || tabButtons.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (TabButtonWidget b : tabButtons) {
            x0 = Math.min(x0, b.getX());
            y0 = Math.min(y0, b.getY());
            x1 = Math.max(x1, b.getX() + b.getWidth());
            y1 = Math.max(y1, b.getY() + b.getHeight());
        }
        Restyle.tabBar(context, x0 - 2, y0 + 1, x1 + 2, y1 - 1);
    }
}
