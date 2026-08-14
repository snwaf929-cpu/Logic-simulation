package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.device.BuiltinDevices;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(value = ComponentLibraryWidget.class, priority = 1200)
public abstract class ComponentLibraryClockMixin {
    private static final int CLOCK_ROW_HEIGHT = 18;
    private static final int CLOCK_ROW_STEP = 19;
    private static final int CONTENT_TOP_OFFSET = 25;
    private static final int CLOCK_OFFSET_IN_CONTENT = 107;
    private static final long[] LOGIC_CLOCK_PRESETS = {
            1L, 2L, 5L, 10L, 20L, 50L, 100L, 200L, 500L,
            1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L,
            1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L, 25_000_000L, 50_000_000L
    };

    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;
    @Shadow private double scroll;

    @Inject(method = "drawComponent", at = @At("RETURN"), cancellable = true)
    private void logic$insertClockRow(GuiGraphicsExtractor graphics, String name, NodeKind kind, int color,
                                      int y, int clipTop, int clipBottom, CallbackInfoReturnable<Integer> cir) {
        if (kind != NodeKind.CONSTANT) return;
        EditorClockRuntime.attach(canvas);
        int clockY = cir.getReturnValue();
        if (clockY + CLOCK_ROW_HEIGHT > clipTop && clockY < clipBottom) {
            ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
            int left = self.getX() + 5;
            int right = self.getX() + self.getWidth() - 5;
            graphics.fill(left, clockY, right, clockY + CLOCK_ROW_HEIGHT - 1, 0xFF1C2229);
            graphics.fill(left, clockY, left + 3, clockY + CLOCK_ROW_HEIGHT - 1, 0xFF5FA8FF);
            graphics.text(Minecraft.getInstance().font, "CLOCK", self.getX() + 13, clockY + 5, 0xFFD7DEE8, false);
            String frequency = EditorNode.formatFrequency(ClockPlacementState.frequencyHz());
            int fx = right - 6 - Minecraft.getInstance().font.width(frequency);
            graphics.text(Minecraft.getInstance().font, frequency, Math.max(self.getX() + 67, fx), clockY + 5, 0xFF9BCBFF, false);
        }
        cir.setReturnValue(clockY + CLOCK_ROW_STEP);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$clockRowClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int y = self.getY() + CONTENT_TOP_OFFSET + CLOCK_OFFSET_IN_CONTENT - (int)Math.round(scroll);
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int clipTop = self.getY() + CONTENT_TOP_OFFSET;
        int clipBottom = self.getY() + self.getHeight() - 30;
        if (y + CLOCK_ROW_HEIGHT <= clipTop || y >= clipBottom) return;
        if (event.x() < left || event.x() >= right || event.y() < y || event.y() >= y + CLOCK_ROW_HEIGHT) return;

        if (event.button() == 0) {
            ClockPlacementState.arm(canvas);
            canvas.setPlacement(NodeKind.CONSTANT);
            status.accept("Place CLOCK — " + EditorNode.formatFrequency(ClockPlacementState.frequencyHz()) + " (RMB changes frequency)");
        } else if (event.button() == 1) {
            long next = logic$nextPreset(ClockPlacementState.frequencyHz());
            ClockPlacementState.setFrequencyHz(next);
            status.accept("CLOCK frequency = " + EditorNode.formatFrequency(next) + " — max 50 MHz");
        } else if (event.button() == 2) {
            boolean running = EditorClockRuntime.toggleAll(canvas);
            status.accept(running ? "CLOCKS RUNNING" : "CLOCKS PAUSED");
        } else return;
        ci.cancel();
    }

    @Inject(method = "drawChip", at = @At("HEAD"), cancellable = true)
    private void logic$hideLegacyDisplayChip(GuiGraphicsExtractor graphics, String chipName, int y, int clipTop, int clipBottom,
                                             CallbackInfoReturnable<Integer> cir) {
        if (chipName != null && BuiltinDevices.DISPLAY.equalsIgnoreCase(chipName.trim())) cir.setReturnValue(y);
    }

    private static long logic$nextPreset(long current) {
        for (long preset : LOGIC_CLOCK_PRESETS) if (preset > current) return preset;
        return LOGIC_CLOCK_PRESETS[0];
    }
}
