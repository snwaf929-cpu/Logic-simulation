package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = CircuitCanvasWidget.class, priority = 1500)
public abstract class CircuitCanvasTerminalRenderMixin {
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private int nodeAccent(EditorNode node) { throw new AssertionError(); }
    @Shadow private LogicValue[] valueForNode(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$terminal(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT) return;
        int x = screenX(node.x), y = screenY(node.y);
        int w = Math.max(8, (int)Math.round(nodeWidth(node) * zoom));
        int h = Math.max(8, (int)Math.round(nodeHeight(node) * zoom));
        LogicValue[] values = valueForNode(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : nodeAccent(node);
        graphics.fill(x, y, x + w, y + h, 0xF012171D);
        graphics.outline(x, y, w, h, border);
        String side = node.kind == NodeKind.INPUT ? "IN" : "OUT";
        String value = logic$format(values);
        logic$smallText(graphics, side, x + w / 2, y + 3, Math.max(5, w - 4), 0xFFAEB9C5);
        logic$smallText(graphics, value, x + w / 2, y + h / 2 - 2, Math.max(5, w - 4), 0xFFFFFFFF);
        List<PortSpec> inputs = safeInputs(node), outputs = safeOutputs(node);
        for (int i = 0; i < inputs.size(); i++) logic$pin(graphics, node.x, node.y + nodeHeight(node) * .5, portDisplayColor(node, i, inputs.get(i), true), validTarget(true));
        for (int i = 0; i < outputs.size(); i++) logic$pin(graphics, node.x + nodeWidth(node), node.y + nodeHeight(node) * .5, portDisplayColor(node, i, outputs.get(i), false), validTarget(false));
        ci.cancel();
    }

    @Unique private void logic$smallText(GuiGraphicsExtractor graphics, String text, int cx, int y, int maxW, int color) {
        int raw = Math.max(1, font().width(text));
        float scale = (float)Math.max(.28, Math.min(1.0, Math.min(zoom, maxW / (double)raw)));
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale);
        graphics.text(font(), text, Math.round(cx / scale - raw / 2f), Math.round(y / scale), color, false);
        graphics.pose().popMatrix();
    }

    @Unique private void logic$pin(GuiGraphicsExtractor graphics, double wx, double wy, int color, boolean target) {
        int x = screenX(Math.round(wx / 6.0) * 6.0), y = screenY(Math.round(wy / 6.0) * 6.0);
        int r = Math.max(1, (int)Math.round((target ? 4.0 : 3.0) * zoom));
        graphics.fill(x-r,y-r,x+r+1,y+r+1,color);
        graphics.outline(x-r-1,y-r-1,r*2+3,r*2+3,0xFF090B0D);
    }

    @Unique private static String logic$format(LogicValue[] values) {
        if (values == null || values.length == 0) return "0";
        for (LogicValue v : values) if (v == LogicValue.UNKNOWN) return "X";
        long n=0; for(int i=0;i<values.length;i++) if(values[i]==LogicValue.HIGH)n|=1L<<i;
        if(values.length==1)return Long.toString(n);
        return Long.toUnsignedString(n,16).toUpperCase();
    }
}
