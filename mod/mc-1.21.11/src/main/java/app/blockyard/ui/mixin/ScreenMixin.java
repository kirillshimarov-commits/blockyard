package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import app.blockyard.ui.mc.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
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
    protected MinecraftClient client;

    @Shadow
    protected abstract void applyBlur(DrawContext context);

    @Unique
    private boolean blockyard$tooltipPause;

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void blockyard$beginRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Restyle.context = context;
        Restyle.begin((Screen) (Object) this);
        blockyard$tooltipPause = false;
    }

    /** Deferred tooltips keep the vanilla font: their boxes are sized for it. */
    @Inject(method = "renderWithTooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/gui/DrawContext;IIF)V", shift = At.Shift.AFTER), require = 0)
    private void blockyard$afterRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Restyle.suspend();
        blockyard$tooltipPause = true;
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void blockyard$endRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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

    @Inject(method = "clearAndInit", at = @At("HEAD"), require = 0)
    private void blockyard$beginReinit(CallbackInfo ci) {
        Restyle.begin((Screen) (Object) this);
    }

    @Inject(method = "clearAndInit", at = @At("RETURN"), require = 0)
    private void blockyard$endReinit(CallbackInfo ci) {
        Restyle.end();
    }

    /** The saving screen has no render of its own: it gets the loading look here. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockyard$message(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Object self = this;
        if (self instanceof MessageScreen screen) {
            Screens.loading(screen, context, screen.getTitle().getString());
            ci.cancel();
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void blockyard$background(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!Restyle.active()) return;
        boolean inWorld = client != null && client.world != null;
        if (inWorld) applyBlur(context);
        Restyle.background(context, inWorld);
        ci.cancel();
    }
}
