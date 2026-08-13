package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CircuitCanvasWidget.class, priority = 1500)
public abstract class CircuitCanvasBusWireTextMixin {
    @Shadow private double zoom;

    @Redirect(method = "drawWire", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
    private void logic$dynamicBusNumber(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow) {
        String number = text;
        if (number.length() >= 2 && number.charAt(0) == '[' && number.charAt(number.length() - 1) == ']') {
            number = number.substring(1, number.length() - 1);
        }
        float scale = (float)Math.max(0.30, Math.min(1.0, zoom));
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        graphics.text(font, number, Math.round(x / scale), Math.round(y / scale), color, shadow);
        graphics.pose().popMatrix();
    }
}
