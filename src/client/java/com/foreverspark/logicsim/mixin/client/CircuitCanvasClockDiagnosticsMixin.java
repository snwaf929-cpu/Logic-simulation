package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ClockRuntimeTelemetry;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Authoritative physical CLOCK performance tooltip. Never uses the budget-capped editor preview as actual MHz. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2100)
public abstract class CircuitCanvasClockDiagnosticsMixin {
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    /**
     * Render after the complete canvas instead of piggybacking on drawPortHoverTooltip(). The old hook was reached
     * through the pin-tooltip path and therefore did not reliably appear when the mouse was over the CLOCK body.
     * TAIL also keeps this panel above nodes, wires, selection handles and ordinary pin tooltips.
     */
    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$clockRuntimeTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        EditorNode node = nodeAt(mouseX, mouseY);
        if (node == null || node.kind != NodeKind.CONSTANT || !node.clockSource) return;

        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        BlockPos circuitPos = ClientEditorBridge.activeCircuitPos();
        ClockRuntimeTelemetry.Snapshot snapshot = ClockRuntimeTelemetry.snapshot(circuitPos);

        List<Line> lines = new ArrayList<>();
        lines.add(new Line("CLOCK RUNTIME", 0xFFEAF4FF));
        lines.add(new Line("Configured   " + logic$formatHz(node.clockFrequencyHz), 0xFFAEC9DE));

        int border = 0xFF6E879A;
        if (circuitPos == null) {
            lines.add(new Line("World runtime unavailable — open a physical Circuit Block", 0xFF9AA6B2));
        } else if (snapshot == null) {
            lines.add(new Line("World runtime: waiting for first ~1 s sample...", 0xFFFFC45C));
        } else {
            ClockRuntimeTelemetry.Health health = snapshot.health();
            border = health.color();
            boolean sameTarget = snapshot.targetHz() == node.clockFrequencyHz;

            if (sameTarget) {
                lines.add(new Line("Actual       " + logic$formatHz(snapshot.actualHz()), 0xFFF1F7FB));
                lines.add(new Line(String.format(Locale.ROOT, "Accuracy     %.2f%%", snapshot.accuracyPercent()), logic$accuracyColor(snapshot)));
            } else {
                lines.add(new Line("Circuit tgt  " + logic$formatHz(snapshot.targetHz()), 0xFFAEC9DE));
                lines.add(new Line("Circuit act  " + logic$formatHz(snapshot.actualHz()), 0xFFF1F7FB));
                lines.add(new Line(String.format(Locale.ROOT, "Circuit acc  %.2f%%", snapshot.accuracyPercent()), logic$accuracyColor(snapshot)));
            }

            lines.add(new Line(String.format(Locale.ROOT, "Debt         %,d edges  (%.3f ms)", snapshot.pendingEdges(), snapshot.debtMillis()),
                    snapshot.debtMillis() >= 2.0 ? 0xFFFFC45C : 0xFFBBD6C2));
            lines.add(new Line(String.format(Locale.ROOT, "Worker busy  %.1f%%", snapshot.workerBusyPercent()),
                    snapshot.workerBusyPercent() >= 92.0 ? 0xFFFF6B6B : snapshot.workerBusyPercent() >= 80.0 ? 0xFFFFC45C : 0xFFBBD6C2));
            lines.add(new Line(String.format(Locale.ROOT, "Headroom     ~%.1f%% worker time", snapshot.workerTimeHeadroomPercent()), 0xFFAEC9DE));
            lines.add(new Line("Status       " + health.label(), health.color()));
        }

        int padding = 6;
        int lineHeight = 11;
        int boxW = 0;
        for (Line line : lines) boxW = Math.max(boxW, font().width(line.text));
        boxW += padding * 2;
        int boxH = padding * 2 + lines.size() * lineHeight;

        int nodeRight = screenX(node.x + nodeWidth(node));
        int x = nodeRight + 10;
        if (x + boxW > self.getX() + self.getWidth() - 3) x = screenX(node.x) - boxW - 10;
        x = Math.max(self.getX() + 3, Math.min(x, self.getX() + self.getWidth() - boxW - 3));
        int y = Math.max(self.getY() + 3, Math.min(screenY(node.y), self.getY() + self.getHeight() - boxH - 3));

        graphics.fill(x, y, x + boxW, y + boxH, 0xF2172028);
        graphics.outline(x, y, boxW, boxH, border);
        int textY = y + padding;
        for (Line line : lines) {
            graphics.text(font(), line.text, x + padding, textY, line.color, false);
            textY += lineHeight;
        }
    }

    @Unique
    private static int logic$accuracyColor(ClockRuntimeTelemetry.Snapshot snapshot) {
        double accuracy = snapshot.accuracyPercent();
        if (accuracy >= 99.0 && accuracy <= 101.0 && snapshot.debtMillis() < 2.0) return 0xFF69D98A;
        if (accuracy >= 95.0) return 0xFFFFC45C;
        return 0xFFFF6B6B;
    }

    @Unique
    private static String logic$formatHz(long hz) {
        if (hz >= 1_000_000_000L) return String.format(Locale.ROOT, "%.3f GHz", hz / 1_000_000_000.0);
        if (hz >= 1_000_000L) return String.format(Locale.ROOT, "%.3f MHz", hz / 1_000_000.0);
        if (hz >= 1_000L) return String.format(Locale.ROOT, "%.3f kHz", hz / 1_000.0);
        return hz + " Hz";
    }

    @Unique private record Line(String text, int color) {}
}
