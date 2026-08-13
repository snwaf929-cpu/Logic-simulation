package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = ComponentLibraryWidget.class, priority = 1210)
public abstract class ComponentLibraryStepMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawStepButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int x = self.getX() + self.getWidth() - 108;
        int y = self.getY() + self.getHeight() - 25;
        int w = 38;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + w, y + 20, hovered ? 0xFF29333E : 0xFF20262D);
        graphics.outline(x, y, w, 20, hovered ? 0xFF5FA8FF : 0xFF46515D);
        var font = Minecraft.getInstance().font;
        graphics.text(font, "STEP", x + (w - font.width("STEP")) / 2, y + 7, hovered ? 0xFFFFFFFF : 0xFFC9D4E0, false);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$stepClock(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int x = self.getX() + self.getWidth() - 108;
        int y = self.getY() + self.getHeight() - 25;
        if (event.x() < x || event.x() >= x + 38 || event.y() < y || event.y() >= y + 20) return;
        int stepped = EditorClockRuntime.stepAll(canvas);
        status.accept(stepped == 0 ? "No CLOCK sources to step" : "STEP: advanced " + stepped + " CLOCK source" + (stepped == 1 ? "" : "s") + " by one edge; clocks paused");
        ci.cancel();
    }
}
