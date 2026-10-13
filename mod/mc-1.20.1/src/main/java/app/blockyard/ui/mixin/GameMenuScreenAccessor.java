package app.blockyard.ui.mixin;

import net.minecraft.client.gui.screen.GameMenuScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the pause screen shows its menu (F3+Esc pauses without one); 1.20.1 has no getter. */
@Mixin(GameMenuScreen.class)
public interface GameMenuScreenAccessor {
    @Accessor("showMenu")
    boolean blockyard$showMenu();
}
