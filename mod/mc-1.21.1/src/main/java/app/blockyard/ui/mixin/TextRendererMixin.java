package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restyled screens measure text in the launcher font, so centering and layout match what is drawn. */
@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @Inject(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$string(String text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }

    @Inject(method = "getWidth(Lnet/minecraft/text/StringVisitable;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$visitable(StringVisitable text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }

    @Inject(method = "getWidth(Lnet/minecraft/text/OrderedText;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$ordered(OrderedText text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }
}
