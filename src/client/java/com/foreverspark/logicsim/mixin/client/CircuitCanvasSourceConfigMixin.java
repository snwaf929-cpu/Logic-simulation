package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.client.screen.SourceConfigScreen;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Double-click a placed CLOCK/RANDOM source to edit its exact parameters. */
@Mixin(value = CircuitCanvasWidget.class, priority = 900)
public abstract class CircuitCanvasSourceConfigMixin {
    @Shadow private Consumer<String> status;
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$openSourceConfig(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 || !doubleClick) return;
        EditorNode node = nodeAt(event.x(), event.y());
        if (node == null || node.kind != NodeKind.CONSTANT) return;

        CircuitCanvasWidget canvas = (CircuitCanvasWidget)(Object)this;
        var parent = Minecraft.getInstance().screen;

        if (node.clockSource) {
            Minecraft.getInstance().gui.setScreen(SourceConfigScreen.clock(parent, node.clockFrequencyHz, hz -> {
                node.clockFrequencyHz = hz;
                node.randomSource = false;
                node.width = 1;
                node.constantValue = 0L;
                canvas.refreshLiveRuntime();
                EditorClockRuntime.attach(canvas);
                status.accept("CLOCK = " + EditorNode.formatFrequency(hz) + " — saved with the board/program");
            }));
            ci.cancel();
            return;
        }

        if (node.randomSource) {
            Minecraft.getInstance().gui.setScreen(SourceConfigScreen.random(parent, node.randomChancePercent, chance -> {
                node.randomChancePercent = chance;
                node.clockSource = false;
                node.width = 1;
                node.constantValue = 0L;
                canvas.refreshLiveRuntime();
                EditorClockRuntime.attach(canvas);
                status.accept("RANDOM = " + chance + "% chance of HIGH per TRIGGER 0 -> 1 edge");
            }));
            ci.cancel();
        }
    }
}
