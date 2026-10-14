package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.Widget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selected row of lists (worlds, key binds, statistics) as a rounded glass highlight. From
 * 1.21.9 rows know their own bounds and the highlight gets the row itself.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListWidgetMixin {
    @Inject(method = "drawSelectionHighlight", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$highlight(DrawContext context, @Coerce Widget entry, int color, CallbackInfo ci) {
        if (!Restyle.active()) return;
        Restyle.selection(context, entry.getX(), entry.getY(), entry.getX() + entry.getWidth(), entry.getY() + entry.getHeight());
        ci.cancel();
    }
}
