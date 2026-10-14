package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.Alignment;
import net.minecraft.client.font.DrawnTextConsumer;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * From 1.21.11 widgets (labels, titles, button captions) draw their text through a text consumer
 * of the draw context instead of drawText: in restyled screens it gets the launcher font too.
 */
@Mixin(targets = "net.minecraft.client.gui.DrawContext$TextConsumerImpl")
public abstract class TextConsumerMixin {
    @Inject(method = "text(Lnet/minecraft/client/font/Alignment;IILnet/minecraft/client/font/DrawnTextConsumer$Transformation;Lnet/minecraft/text/OrderedText;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$text(Alignment alignment, int x, int y, DrawnTextConsumer.Transformation transformation, OrderedText text, CallbackInfo ci) {
        if (text == null || Restyle.context == null || !Restyle.active()) return;
        // Widths are already the launcher font's (TextRendererMixin), so alignment matches what is drawn.
        int left = alignment.getAdjustedX(x, MinecraftClient.getInstance().textRenderer, text);
        int a = Math.max(0, Math.min(255, Math.round(transformation.opacity() * 255)));
        Restyle.drawText(Restyle.context, text, left, y, (a << 24) | 0xFFFFFF);
        ci.cancel();
    }
}
