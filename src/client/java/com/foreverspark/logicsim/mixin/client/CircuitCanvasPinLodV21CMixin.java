package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps all Editor V2 and specialized DEVICE/CHIP pin renderers on the same zoom-aware LOD policy. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2940)
public abstract class CircuitCanvasPinLodV21CMixin {
    @Shadow private double zoom;

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$publishPinLod(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        EditorPinGeometry.setCanvasZoom(zoom);
    }
}
