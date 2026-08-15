package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ClockPlacementState;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import com.foreverspark.logicsim.client.screen.RandomPlacementState;
import com.foreverspark.logicsim.client.screen.SourceConfigScreen;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/** Adds CLOCK/RANDOM infrastructure only. Physical DEVICE nodes are discovered from the world, never placed here. */
@Mixin(value = ComponentLibraryWidget.class, priority = 1200)
public abstract class ComponentLibraryClockMixin {
    private static final int EXTRA_ROW_HEIGHT = 18;
    private static final int EXTRA_ROW_STEP = 19;
    private static final int CONTENT_TOP_OFFSET = 25;

    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;

    @Unique private int logic$clockRowY = Integer.MIN_VALUE;
    @Unique private int logic$randomRowY = Integer.MIN_VALUE;

    @Inject(method = "drawComponent", at = @At("RETURN"), cancellable = true)
    private void logic$insertExtraRows(GuiGraphicsExtractor graphics, String name, NodeKind kind, int color,
                                       int y, int clipTop, int clipBottom, CallbackInfoReturnable<Integer> cir) {
        if (kind != NodeKind.CONSTANT) return;
        EditorClockRuntime.attach(canvas);
        int rowY = cir.getReturnValue();
        logic$clockRowY = rowY;
        logic$drawRow(graphics, rowY, clipTop, clipBottom, "CLOCK", 0xFF5FA8FF,
                EditorNode.formatFrequency(ClockPlacementState.frequencyHz()), 0xFF9BCBFF);
        rowY += EXTRA_ROW_STEP;
        logic$randomRowY = rowY;
        logic$drawRow(graphics, rowY, clipTop, clipBottom, "RANDOM", 0xFFB06CE8,
                RandomPlacementState.chancePercent() + "% HIGH", 0xFFC9A7E8);
        cir.setReturnValue(rowY + EXTRA_ROW_STEP);
    }

    @Unique
    private void logic$drawRow(GuiGraphicsExtractor graphics, int rowY, int clipTop, int clipBottom,
                               String title, int accent, String rightText, int rightColor) {
        if (rowY + EXTRA_ROW_HEIGHT <= clipTop || rowY >= clipBottom) return;
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        graphics.fill(left, rowY, right, rowY + EXTRA_ROW_HEIGHT - 1, 0xFF1C2229);
        graphics.fill(left, rowY, left + 3, rowY + EXTRA_ROW_HEIGHT - 1, accent);
        graphics.text(Minecraft.getInstance().font, title, self.getX() + 13, rowY + 5, 0xFFD7DEE8, false);
        int fx = right - 6 - Minecraft.getInstance().font.width(rightText);
        graphics.text(Minecraft.getInstance().font, rightText, Math.max(self.getX() + 67, fx), rowY + 5, rightColor, false);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$extraRowClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        ComponentLibraryWidget self = (ComponentLibraryWidget)(Object)this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int clipTop = self.getY() + CONTENT_TOP_OFFSET;
        int clipBottom = self.getY() + self.getHeight() - 30;
        if (event.x() < left || event.x() >= right) return;

        if (logic$visibleHit(logic$clockRowY, event.y(), clipTop, clipBottom)) {
            if (event.button() == 0) {
                RandomPlacementState.disarm();
                ClockPlacementState.arm(canvas);
                canvas.setPlacement(NodeKind.CONSTANT);
                status.accept("Place CLOCK at " + EditorNode.formatFrequency(ClockPlacementState.frequencyHz())
                        + " — right-click CLOCK in the library to enter an exact Hz / kHz / MHz value.");
            } else if (event.button() == 1) {
                var parent = EditorScreenContext.current();
                Minecraft.getInstance().gui.setScreen(SourceConfigScreen.clock(parent, ClockPlacementState.frequencyHz(), hz -> {
                    ClockPlacementState.setFrequencyHz(hz);
                    status.accept("CLOCK placement frequency = " + EditorNode.formatFrequency(hz));
                }));
            } else if (event.button() == 2) {
                boolean running = EditorClockRuntime.toggleAll(canvas);
                status.accept(running ? "CLOCKS RUNNING" : "CLOCKS PAUSED");
            } else return;
            ci.cancel();
            return;
        }

        if (logic$visibleHit(logic$randomRowY, event.y(), clipTop, clipBottom)) {
            if (event.button() == 0) {
                ClockPlacementState.disarm();
                RandomPlacementState.arm(canvas);
                canvas.setPlacement(NodeKind.CONSTANT);
                status.accept("Place RANDOM — " + RandomPlacementState.chancePercent()
                        + "% chance of HIGH on each TRIGGER 0 -> 1 edge. Right-click RANDOM to set the chance.");
            } else if (event.button() == 1) {
                var parent = EditorScreenContext.current();
                Minecraft.getInstance().gui.setScreen(SourceConfigScreen.random(parent, RandomPlacementState.chancePercent(), chance -> {
                    RandomPlacementState.setChancePercent(chance);
                    status.accept("RANDOM placement chance = " + chance + "% HIGH per rising edge");
                }));
            } else return;
            ci.cancel();
        }
    }

    @Unique
    private static boolean logic$visibleHit(int rowY, double mouseY, int clipTop, int clipBottom) {
        return rowY != Integer.MIN_VALUE && rowY + EXTRA_ROW_HEIGHT > clipTop && rowY < clipBottom
                && mouseY >= rowY && mouseY < rowY + EXTRA_ROW_HEIGHT;
    }

    /** Old fake built-in IDs stay hidden if stale chip files exist on disk. */
    @Inject(method = "drawChip", at = @At("HEAD"), cancellable = true)
    private void logic$hideLegacyFakeChip(GuiGraphicsExtractor graphics, String chipName, int y, int clipTop, int clipBottom,
                                          CallbackInfoReturnable<Integer> cir) {
        if (BuiltinDevices.isRemovedFake(chipName) || BuiltinDevices.isDisplay(chipName)) cir.setReturnValue(y);
    }
}
