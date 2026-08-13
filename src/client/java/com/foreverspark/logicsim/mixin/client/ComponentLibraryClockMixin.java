package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ClockPlacementState;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = ComponentLibraryWidget.class, priority = 1200)
public abstract class ComponentLibraryClockMixin {
    private static final long[] LOGIC_CLOCK_PRESETS = {
            1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 5_000_000L, 10_000_000L
    };

    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawClockButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int x = self.getX() + self.getWidth() - 66;
        int y = self.getY() + self.getHeight() - 25;
        int w = 34;
        int h = 20;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, hovered ? 0xFF29333E : 0xFF20262D);
        graphics.outline(x, y, w, h, hovered ? 0xFF6CB8FF : 0xFF46515D);
        graphics.text(Minecraft.getInstance().font, "CLK", x + 7, y + 7, hovered ? 0xFFFFFFFF : 0xFFC9D4E0, false);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$clockButton(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int x = self.getX() + self.getWidth() - 66;
        int y = self.getY() + self.getHeight() - 25;
        if (event.x() < x || event.x() >= x + 34 || event.y() < y || event.y() >= y + 20) return;

        if (event.button() == 0) {
            ClockPlacementState.arm(canvas);
            canvas.setPlacement(NodeKind.CONSTANT);
            status.accept("Place CLOCK — " + EditorNode.formatFrequency(ClockPlacementState.frequencyHz()) + " virtual source");
            ci.cancel();
            return;
        }
        if (event.button() == 1) {
            long next = logic$nextPreset(ClockPlacementState.frequencyHz());
            ClockPlacementState.setFrequencyHz(next);
            status.accept("CLOCK placement frequency = " + EditorNode.formatFrequency(next));
            ci.cancel();
            return;
        }
        if (event.button() == 2) {
            boolean running = EditorClockRuntime.toggleAll(canvas);
            status.accept(running ? "CLOCKS RUNNING" : "CLOCKS PAUSED");
            ci.cancel();
        }
    }

    private static long logic$nextPreset(long current) {
        for (long preset : LOGIC_CLOCK_PRESETS) if (preset > current) return preset;
        return LOGIC_CLOCK_PRESETS[0];
    }
}
