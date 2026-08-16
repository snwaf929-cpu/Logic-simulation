package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only branch/tap markers retained after removing the obsolete pre-V2.1 branch interaction state machine. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1260)
public abstract class CircuitCanvasBranchMarkerV21CMixin {
    @Shadow private CircuitDocument document;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$renderCanonicalBranchMarkers(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        for (WireConnection wire : document.wires) {
            RoutePoint tap = wire.branchStart();
            if (tap == null) continue;
            int x = screenX(tap.x());
            int y = screenY(tap.y());
            int color = logic$wireColor(wire);
            graphics.fill(x - 3, y - 3, x + 4, y + 4, color);
            graphics.outline(x - 4, y - 4, 9, 9, 0xFF090C10);
        }
    }

    private static int logic$wireColor(WireConnection wire) {
        // Marker is topology metadata, not a simulator source; keep the same neutral live-wire palette used by V2.1.
        return wire == null ? 0xFF79C4FF : 0xFF8DB7FF;
    }
}
