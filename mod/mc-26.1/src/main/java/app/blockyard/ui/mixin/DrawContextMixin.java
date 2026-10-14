package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Text, widget sprites and menu textures of restyled screens. From 1.21.6 sprites and textures
 * take the render pipeline as their first argument, and text drawing returns nothing.
 */
@Mixin(GuiGraphics.class)
public abstract class DrawContextMixin {
    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void blockyard$string(Font renderer, String text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !Restyle.active()) return;
        Restyle.drawText((GuiGraphics) (Object) this, text, x, y, color);
        ci.cancel();
    }

    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void blockyard$ordered(Font renderer, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !Restyle.active()) return;
        Restyle.drawText((GuiGraphics) (Object) this, text, x, y, color);
        ci.cancel();
    }

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$sprite(RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height, CallbackInfo ci) {
        if (Restyle.sprite((GuiGraphics) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$spriteTinted(RenderPipeline pipeline, Identifier texture, int x, int y, int width, int height, int color, CallbackInfo ci) {
        if (Restyle.sprite((GuiGraphics) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$texture(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int width, int height, int texW, int texH, CallbackInfo ci) {
        if (Restyle.texture((GuiGraphics) (Object) this, texture, x, y, width, height)) ci.cancel();
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), require = 0)
    private void blockyard$tooltipStart(CallbackInfo ci) {
        Restyle.suspend();
    }

    @Inject(method = "renderTooltip", at = @At("RETURN"), require = 0)
    private void blockyard$tooltipEnd(CallbackInfo ci) {
        Restyle.resume();
    }
}
