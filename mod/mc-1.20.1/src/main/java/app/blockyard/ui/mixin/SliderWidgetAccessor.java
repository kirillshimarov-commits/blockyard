package app.blockyard.ui.mixin;

import net.minecraft.client.gui.widget.SliderWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slider's position (0..1), for drawing it in the launcher style. */
@Mixin(SliderWidget.class)
public interface SliderWidgetAccessor {
    @Accessor("value")
    double blockyard$value();
}
