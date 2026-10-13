package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps vanilla menus for the Blockyard ones as they open. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen blockyard$replaceScreen(Screen screen) {
        return Screens.replace((MinecraftClient) (Object) this, screen);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void blockyard$selftest(CallbackInfo ci) {
        if (app.blockyard.ui.mc.Selftest.ENABLED) app.blockyard.ui.mc.Selftest.tick((MinecraftClient) (Object) this);
    }
}
