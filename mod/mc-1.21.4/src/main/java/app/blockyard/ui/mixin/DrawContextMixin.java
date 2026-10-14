package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Text, widget sprites and menu textures of restyled screens. From 1.21.2 sprites and textures
 * take the render layer to draw with as their first argument.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$string(TextRenderer renderer, String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (text == null || !Restyle.active()) return;
        cir.setReturnValue(Restyle.drawText((DrawContext) (Object) this, text, x, y, color));
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void blockyard$ordered(TextRenderer renderer, OrderedText text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (text == null || !Restyle.active()) return;
        cir.setReturnValue(Restyle.drawText((DrawContext) (Object) this, text, x, y, color));
    }

    @Inject(method = "drawGuiTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$sprite(Function<Identifier, RenderLayer> layers, Identifier texture, int x, int y, int width, int height, CallbackInfo ci) {
        if (Restyle.sprite((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawGuiTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$spriteTinted(Function<Identifier, RenderLayer> layers, Identifier texture, int x, int y, int width, int height, int color, CallbackInfo ci) {
        if (Restyle.sprite((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$texture(Function<Identifier, RenderLayer> layers, Identifier texture, int x, int y, float u, float v, int width, int height, int texW, int texH, CallbackInfo ci) {
        if (Restyle.texture((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;IILnet/minecraft/client/gui/tooltip/TooltipPositioner;Lnet/minecraft/util/Identifier;)V",
            at = @At("HEAD"), require = 0)
    private void blockyard$tooltipStart(CallbackInfo ci) {
        Restyle.suspend();
    }

    @Inject(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;IILnet/minecraft/client/gui/tooltip/TooltipPositioner;Lnet/minecraft/util/Identifier;)V",
            at = @At("RETURN"), require = 0)
    private void blockyard$tooltipEnd(CallbackInfo ci) {
        Restyle.resume();
    }
}
