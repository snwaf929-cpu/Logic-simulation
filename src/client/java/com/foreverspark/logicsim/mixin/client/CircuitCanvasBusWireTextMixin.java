package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Bus-width labels are screen-space annotations: zoom moves them, but never changes their pixel size. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1500)
public abstract class CircuitCanvasBusWireTextMixin {
    @Redirect(method = "drawWire", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
    private void logic$screenSpaceBusNumber(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow) {
        String number = text;
        if (number.length() >= 2 && number.charAt(0) == '[' && number.charAt(number.length() - 1) == ']') {
            number = number.substring(1, number.length() - 1);
        }
        graphics.text(font, number, x, y, color, shadow);
    }
}
