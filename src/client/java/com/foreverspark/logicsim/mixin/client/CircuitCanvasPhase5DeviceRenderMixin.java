package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Distinct physical DEVICE presentation with explicit named/typed ports and persistent disconnect state. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2700)
public abstract class CircuitCanvasPhase5DeviceRenderMixin {
    @Unique private static final double DEVICE_WIDTH = 126.0;
    @Unique private static final double DEVICE_PORT_STEP = 18.0;

    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }

    @Inject(method = "nodeWidth", at = @At("HEAD"), cancellable = true)
    private void logic$deviceWidth(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node != null && node.isExternalDevice()) cir.setReturnValue(DEVICE_WIDTH);
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$deviceHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node == null || !node.isExternalDevice()) return;
        int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        cir.setReturnValue(Math.max(54.0, 42.0 + Math.max(0, ports - 1) * DEVICE_PORT_STEP));
    }

    @Inject(method = "portStep", at = @At("HEAD"), cancellable = true)
    private void logic$devicePortStep(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node != null && node.isExternalDevice()) cir.setReturnValue(DEVICE_PORT_STEP);
    }

    @Inject(method = "drawNode", at = @At("HEAD"), cancellable = true)
    private void logic$drawDevice(GuiGraphicsExtractor graphics, EditorNode node, CallbackInfo ci) {
        if (node == null || !node.isExternalDevice()) return;
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(40, (int)Math.round(DEVICE_WIDTH * zoom));
        int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        double height = Math.max(54.0, 42.0 + Math.max(0, ports - 1) * DEVICE_PORT_STEP);
        int h = Math.max(28, (int)Math.round(height * zoom));

        int accent = switch (node.externalDeviceState) {
            case CONNECTED -> 0xFF4DBA78;
            case DISCONNECTED -> 0xFFE05B5B;
            case UNKNOWN -> 0xFFB47A46;
        };
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : accent;

        graphics.fill(x, y, x + w, y + h, 0xF012171C);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.max(3, (int)Math.round(4 * zoom)), accent);
        String title = node.externalDeviceType == null ? "DEVICE" : node.externalDeviceType.label();
        if (zoom >= 0.48) graphics.text(Minecraft.getInstance().font, trim(title, w - 8), x + 5, y + 8, 0xFFF0F4F7, true);

        String state = node.externalDeviceState == null ? "UNKNOWN" : node.externalDeviceState.name();
        if (zoom >= 0.58) graphics.text(Minecraft.getInstance().font, state, x + 5, y + h - 13, accent, false);

        List<PortSpec> inputs = safeInputs(node);
        for (int port = 0; port < inputs.size(); port++) {
            PortSpec spec = inputs.get(port);
            double py = node.y + 30.0 + port * DEVICE_PORT_STEP;
            int sx = screenX(node.x), sy = screenY(py);
            EditorPinGeometry.draw(graphics, sx, sy, spec.width(), portDisplayColor(node, port, spec, true));
            if (zoom >= 0.60) {
                String label = logic$portLabel(spec);
                graphics.text(Minecraft.getInstance().font, label, sx + 7, sy - 4, 0xFFC4CDD6, false);
            }
        }

        List<PortSpec> outputs = safeOutputs(node);
        for (int port = 0; port < outputs.size(); port++) {
            PortSpec spec = outputs.get(port);
            double py = node.y + 30.0 + port * DEVICE_PORT_STEP;
            int sx = screenX(node.x + DEVICE_WIDTH), sy = screenY(py);
            EditorPinGeometry.draw(graphics, sx, sy, spec.width(), portDisplayColor(node, port, spec, false));
            if (zoom >= 0.60) {
                String label = logic$portLabel(spec);
                int labelX = sx - 7 - Minecraft.getInstance().font.width(label);
                graphics.text(Minecraft.getInstance().font, label, labelX, sy - 4, 0xFFC4CDD6, false);
            }
        }
        ci.cancel();
    }

    @Unique
    private static String logic$portLabel(PortSpec spec) {
        if (spec == null) return "?";
        return spec.name() + " [" + spec.width() + "]";
    }

    @Unique
    private static String trim(String value, int px) {
        if (value == null) return "";
        var font = Minecraft.getInstance().font;
        String result = value;
        while (result.length() > 1 && font.width(result) > px) result = result.substring(0, result.length() - 1);
        return result;
    }
}
