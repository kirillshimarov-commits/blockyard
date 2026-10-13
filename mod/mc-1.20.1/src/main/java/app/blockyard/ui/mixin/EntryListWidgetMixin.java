package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The selected row of lists (worlds, key binds, statistics) as a rounded glass highlight. */
@Mixin(EntryListWidget.class)
public abstract class EntryListWidgetMixin {
    @Inject(method = "drawSelectionHighlight", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$highlight(DrawContext context, int y, int entryWidth, int entryHeight, int borderColor, int fillColor, CallbackInfo ci) {
        Object self = this;
        if (!Restyle.active() || !(self instanceof ClickableWidget w)) return;
        int x0 = w.getX() + (w.getWidth() - entryWidth) / 2, x1 = w.getX() + (w.getWidth() + entryWidth) / 2;
        Restyle.selection(context, x0, y - 2, x1, y + entryHeight + 2);
        ci.cancel();
    }
}
