package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * From 1.21.11 widgets (labels, titles, button captions) draw their text through a text consumer
 * of the draw context instead of drawText: in restyled screens it gets the launcher font too.
 */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphics$RenderingTextCollector")
public abstract class TextConsumerMixin {
    @Inject(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$text(TextAlignment alignment, int x, int y, ActiveTextCollector.Parameters transformation, FormattedCharSequence text, CallbackInfo ci) {
        if (text == null || Restyle.context == null || !Restyle.active()) return;
        // Widths are already the launcher font's (TextRendererMixin), so alignment matches what is drawn.
        int left = alignment.calculateLeft(x, Minecraft.getInstance().font, text);
        int a = Math.max(0, Math.min(255, Math.round(transformation.opacity() * 255)));
        Restyle.drawText(Restyle.context, text, left, y, (a << 24) | 0xFFFFFF);
        ci.cancel();
    }
}
