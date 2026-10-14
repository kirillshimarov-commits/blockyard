package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restyled screens measure text in the launcher font, so centering and layout match what is drawn. */
@Mixin(Font.class)
public abstract class TextRendererMixin {
    @Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$string(String text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$visitable(FormattedText text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }

    @Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$ordered(FormattedCharSequence text, CallbackInfoReturnable<Integer> cir) {
        if (Restyle.active()) cir.setReturnValue(Restyle.width(text));
    }
}
