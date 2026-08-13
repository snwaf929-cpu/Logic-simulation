package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CircuitCanvasWidget.class, priority = 1450)
public abstract class ClockNodeLabelMixin {
    @Shadow private CircuitDocument document;
    @Shadow private double zoom;
    @Shadow private int screenX(double x) { throw new AssertionError(); }
    @Shadow private int screenY(double y) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$labels(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (zoom < 0.70) return;
        var font = Minecraft.getInstance().font;
        for (EditorNode node : document.nodes) {
            if (node.kind != NodeKind.CONSTANT || !node.clockSource) continue;
            int x = screenX(node.x);
            int y = screenY(node.y);
            int w = Math.max(18, (int)Math.round(nodeWidth(node) * zoom));
            String text = "CLK " + shortFrequency(node.clockFrequencyHz);
            graphics.fill(x + 3, y + 6, x + w - 3, y + 20, 0xFF191F26);
            graphics.text(font, text, x + Math.max(3, (w - font.width(text)) / 2), y + 9, 0xFFF2F5F8, false);
        }
    }

    private static String shortFrequency(long hz) {
        if (hz >= 1_000_000L && hz % 1_000_000L == 0L) return (hz / 1_000_000L) + "M";
        if (hz >= 1_000L && hz % 1_000L == 0L) return (hz / 1_000L) + "K";
        return Long.toString(hz);
    }
}
