package app.blockyard.ui.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the self test (CI screenshots) every tick. Screens are swapped in {@link GuiMixin}. */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void blockyard$selftest(CallbackInfo ci) {
        if (app.blockyard.ui.mc.Selftest.ENABLED) app.blockyard.ui.mc.Selftest.tick((Minecraft) (Object) this);
    }
}
