package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import app.blockyard.ui.mc.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the launcher look on while a vanilla screen lays itself out and draws, and gives it our background. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Shadow
    protected Minecraft minecraft;

    @Shadow
    protected abstract void extractBlurredBackground(GuiGraphicsExtractor context);

    @Unique
    private boolean blockyard$tooltipPause;

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void blockyard$beginRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Restyle.context = context;
        Restyle.begin((Screen) (Object) this);
        blockyard$tooltipPause = false;
    }

    /** Deferred tooltips keep the vanilla font: their boxes are sized for it. */
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", shift = At.Shift.AFTER), require = 0)
    private void blockyard$afterRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Restyle.suspend();
        blockyard$tooltipPause = true;
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void blockyard$endRender(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (blockyard$tooltipPause) Restyle.resume();
        blockyard$tooltipPause = false;
        Restyle.end();
    }

    @Inject(method = "init(II)V", at = @At("HEAD"))
    private void blockyard$beginInit(int width, int height, CallbackInfo ci) {
        Restyle.begin((Screen) (Object) this);
    }

    @Inject(method = "init(II)V", at = @At("RETURN"))
    private void blockyard$endInit(int width, int height, CallbackInfo ci) {
        Restyle.end();
    }

    @Inject(method = "resize", at = @At("HEAD"), require = 0)
    private void blockyard$beginResize(int width, int height, CallbackInfo ci) {
        Restyle.begin((Screen) (Object) this);
    }

    @Inject(method = "resize", at = @At("RETURN"), require = 0)
    private void blockyard$endResize(int width, int height, CallbackInfo ci) {
        Restyle.end();
    }

    @Inject(method = "rebuildWidgets", at = @At("HEAD"), require = 0)
    private void blockyard$beginReinit(CallbackInfo ci) {
        Restyle.begin((Screen) (Object) this);
    }

    @Inject(method = "rebuildWidgets", at = @At("RETURN"), require = 0)
    private void blockyard$endReinit(CallbackInfo ci) {
        Restyle.end();
    }

    /** The saving screen has no render of its own: it gets the loading look here. */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void blockyard$message(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Object self = this;
        if (self instanceof GenericMessageScreen screen) {
            Screens.loading(screen, context, screen.getTitle().getString());
            ci.cancel();
        }
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void blockyard$background(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        boolean inWorld = minecraft != null && minecraft.level != null;
        if (inWorld) extractBlurredBackground(context);
        Restyle.background(context, inWorld);
        ci.cancel();
    }
}
