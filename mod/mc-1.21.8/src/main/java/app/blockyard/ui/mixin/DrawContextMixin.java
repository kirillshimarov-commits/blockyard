package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Text, widget sprites and menu textures of restyled screens. From 1.21.6 sprites and textures
 * take the render pipeline as their first argument, and text drawing returns nothing.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void blockyard$string(TextRenderer renderer, String text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !Restyle.active()) return;
        Restyle.drawText((DrawContext) (Object) this, text, x, y, color);
        ci.cancel();
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void blockyard$ordered(TextRenderer renderer, OrderedText text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !Restyle.active()) return;
        Restyle.drawText((DrawContext) (Object) this, text, x, y, color);
        ci.cancel();
    }

    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$sprite(RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height, CallbackInfo ci) {
        if (Restyle.sprite((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$spriteTinted(RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height, int color, CallbackInfo ci) {
        if (Restyle.sprite((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$texture(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int width, int height, int texW, int texH, CallbackInfo ci) {
        if (Restyle.texture((DrawContext) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "drawTooltipImmediately", at = @At("HEAD"), require = 0)
    private void blockyard$tooltipStart(CallbackInfo ci) {
        Restyle.suspend();
    }

    @Inject(method = "drawTooltipImmediately", at = @At("RETURN"), require = 0)
    private void blockyard$tooltipEnd(CallbackInfo ci) {
        Restyle.resume();
    }
}
