package app.blockyard.ui.mixin;

import app.blockyard.ui.mc.Restyle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Text fields of restyled screens. In 1.20.1 they draw a light border and a black inside with two
 * fills (later versions use sprites); the border becomes the launcher's field, the inside goes away.
 */
@Mixin(TextFieldWidget.class)
public abstract class TextFieldWidgetMixin {
    @Redirect(method = "renderButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"), require = 0)
    private void blockyard$fill(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        if (!Restyle.active()) {
            context.fill(x1, y1, x2, y2, color);
        } else if (color != 0xFF000000) {
            Restyle.textField(context, x1, y1, x2 - x1, y2 - y1, color == -1);
        }
    }
}
