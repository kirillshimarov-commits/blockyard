package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lists of restyled screens (key binds, worlds, statistics): no dirt behind and above/below them,
 * and the selected row as a rounded glass highlight.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListWidgetMixin {
    @Shadow
    private boolean renderBackground;

    @Shadow
    private boolean renderHorizontalShadows;

    @Shadow
    protected int left;

    @Shadow
    protected int width;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void blockyard$noDirt(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        renderBackground = false;
        renderHorizontalShadows = false;
    }

    @Inject(method = "drawSelectionHighlight", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$highlight(DrawContext context, int y, int entryWidth, int entryHeight, int borderColor, int fillColor, CallbackInfo ci) {
        if (!Restyle.active()) return;
        int x0 = left + (width - entryWidth) / 2, x1 = left + (width + entryWidth) / 2;
        Restyle.selection(context, x0, y - 2, x1, y + entryHeight + 2);
        ci.cancel();
    }
}
