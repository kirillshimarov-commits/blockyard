package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Screens;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the loading screens in the Blockyard style instead of the vanilla dirt and text. */
@Mixin({ConnectScreen.class, LevelLoadingScreen.class, DownloadingTerrainScreen.class})
public abstract class LoadingScreensMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void blockyard$render(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Object self = this;
        String status;
        if (self instanceof ConnectScreen) status = "Подключаемся к серверу…";
        else if (self instanceof LevelLoadingScreen) status = "Загружаем мир…";
        else if (self instanceof DownloadingTerrainScreen) status = "Загружаем местность…";
        else status = ((Screen) self).getTitle().getString();
        Screens.loading((Screen) self, context, status);
        ci.cancel();
    }
}
