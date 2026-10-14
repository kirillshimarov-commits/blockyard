package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps vanilla menus for the Blockyard ones as they open. From 26.2 screens belong to Gui, not Minecraft. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen blockyard$replaceScreen(Screen screen) {
        return Screens.replace(Minecraft.getInstance(), screen);
    }
}
