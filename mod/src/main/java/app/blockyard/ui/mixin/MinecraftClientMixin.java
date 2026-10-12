package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps vanilla menus for the Blockyard ones as they open. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen blockyard$replaceScreen(Screen screen) {
        return Screens.replace((MinecraftClient) (Object) this, screen);
    }
}
