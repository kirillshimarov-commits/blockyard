package app.blockyard.ui.mixin;

import app.blockyard.ui.BlockyardUi;
import app.blockyard.ui.screen.HomeScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Every time the game opens its title screen, open the Blockyard home screen instead. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen blockyard$replaceTitleScreen(Screen screen) {
        if (screen instanceof TitleScreen) {
            if (BlockyardUi.vanillaOnce) {
                BlockyardUi.vanillaOnce = false;
                return screen;
            }
            return new HomeScreen();
        }
        return screen;
    }
}
