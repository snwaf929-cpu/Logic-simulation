package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.editor.model.EditorNode;
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

/**
 * Clean custom-chip renderer with screen-space invariant labels and pins.
 * Zoom changes the world/body geometry only; labels are shortened rather than scaled.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 1250)
public abstract class CircuitCanvasCustomChipTextMixin {
    @Shadow private double zoom;
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private boolean isNodeSelected(int nodeId) { throw new AssertionError(); }
    @Shadow private int nodeAccent(EditorNode node) { throw new AssertionError(); }
    @Shadow private Font font() { throw new AssertionError(); }

    @Inject(method = "drawCustomChipNode", at = @At("HEAD"), cancellable = true)
    private void logic$dynamicCustomChip(GuiGraphicsExtractor graphics, EditorNode node, List<PortSpec> inputs, List<PortSpec> outputs,
                                         int x, int y, int w, int h, CallbackInfo ci) {
        if (BuiltinDevices.isDisplay(node.chipName)) {
            logic$drawScreenOutput(graphics, node, inputs, outputs, x, y, w, h);
            ci.cancel();
            return;
        }

        int accent = nodeAccent(node);
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : logic$darken(accent, 0.92);
        graphics.fill(x, y, x + w, y + h, 0xF0191F26);
        graphics.outline(x, y, w, h, border);
        if (w > 4 && h > 4) graphics.outline(x + 1, y + 1, w - 2, h - 2, 0xFF252E37);

        int strip = Math.max(2, (int)Math.round(4.0 * zoom));
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.min(h - 1, strip + 1), accent);

        int padX = 4;
        int padY = 3;
        logic$textInRect(
                graphics,
                node.displayName(),
                x + padX,
                y + strip + padY,
                x + w - padX,
                y + h - padY,
                0xFFF2F5F8
        );

        logic$ports(graphics, node, inputs, outputs);
        ci.cancel();
    }

    @Unique
    private void logic$drawScreenOutput(GuiGraphicsExtractor graphics, EditorNode node, List<PortSpec> inputs, List<PortSpec> outputs,
                                        int x, int y, int w, int h) {
        int border = isNodeSelected(node.id) ? 0xFFFFFFFF : 0xFF4A9CAD;
        graphics.fill(x, y, x + w, y + h, 0xF010171B);
        graphics.outline(x, y, w, h, border);
        if (w > 4 && h > 4) graphics.outline(x + 1, y + 1, w - 2, h - 2, 0xFF233238);

        int strip = Math.max(2, (int)Math.round(4.0 * zoom));
        graphics.fill(x + 1, y + 1, x + w - 1, y + Math.min(h - 1, strip + 1), BuiltinDevices.DISPLAY_COLOR);

        int px = 5;
        int py = 4;
        int innerLeft = x + px;
        int innerRight = x + w - px;
        int innerTop = y + strip + py;
        int innerBottom = y + h - py;

        int monitorTop = innerTop + Math.max(10, (innerBottom - innerTop) / 4);
        int monitorBottom = innerBottom - Math.max(8, (innerBottom - innerTop) / 5);
        if (innerRight - innerLeft > 18 && monitorBottom - monitorTop > 12) {
            graphics.fill(innerLeft, monitorTop, innerRight, monitorBottom, 0xFF071014);
            graphics.outline(innerLeft, monitorTop, innerRight - innerLeft, monitorBottom - monitorTop, 0xFF357987);
            int glowInset = 2;
            if (innerRight - innerLeft > glowInset * 2 + 4 && monitorBottom - monitorTop > glowInset * 2 + 4) {
                graphics.outline(innerLeft + glowInset, monitorTop + glowInset,
                        innerRight - innerLeft - glowInset * 2,
                        monitorBottom - monitorTop - glowInset * 2,
                        0xFF1D4A53);
            }
        }

        logic$textInRect(graphics, BuiltinDevices.DISPLAY_LABEL,
                innerLeft, innerTop, innerRight, monitorTop - 1, 0xFFF0FAFC);
        logic$textInRect(graphics, "PIXEL  ->  DATA64",
                innerLeft, monitorBottom + 1, innerRight, innerBottom, 0xFF86C7D3);

        logic$ports(graphics, node, inputs, outputs);
    }

    @Unique
    private void logic$ports(GuiGraphicsExtractor graphics, EditorNode node, List<PortSpec> inputs, List<PortSpec> outputs) {
        for (int port = 0; port < inputs.size(); port++) {
            double py = centeredPortY(node, port, inputs.size());
            logic$port(graphics, node.x, py, inputs.get(port).width(),
                    portDisplayColor(node, port, inputs.get(port), true));
        }
        for (int port = 0; port < outputs.size(); port++) {
            double py = centeredPortY(node, port, outputs.size());
            logic$port(graphics, node.x + nodeWidth(node), py, outputs.get(port).width(),
                    portDisplayColor(node, port, outputs.get(port), false));
        }
    }

    /** Fixed one-font-pixel-scale text. If the body is too narrow, truncate instead of scaling. */
    @Unique
    private void logic$textInRect(GuiGraphicsExtractor graphics, String text,
                                  int left, int top, int right, int bottom, int color) {
        if (text == null || text.isBlank()) return;
        int availableWidth = Math.max(0, right - left);
        int availableHeight = Math.max(0, bottom - top);
        if (availableWidth <= 0 || availableHeight <= 2) return;

        String shown = logic$fit(text, availableWidth);
        if (shown.isEmpty()) return;
        int rawWidth = font().width(shown);
        int tx = left + Math.max(0, (availableWidth - rawWidth) / 2);
        int ty = top + Math.max(0, (availableHeight - 8) / 2);
        graphics.text(font(), shown, tx, ty, color, false);
    }

    @Unique
    private String logic$fit(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font().width(text) <= maxWidth) return text;
        String suffix = "…";
        if (font().width(suffix) > maxWidth) return "";
        int end = text.length();
        while (end > 0 && font().width(text.substring(0, end) + suffix) > maxWidth) end--;
        return text.substring(0, end) + suffix;
    }

    @Unique
    private void logic$port(GuiGraphicsExtractor graphics, double worldX, double worldY, int width, int color) {
        int x = screenX(EditorGrid.snap(worldX));
        int y = screenY(EditorGrid.snap(worldY));
        EditorPinGeometry.draw(graphics, x, y, width, color);
    }

    @Unique
    private static int logic$darken(int color, double factor) {
        int r = (int)(((color >>> 16) & 0xFF) * factor);
        int g = (int)(((color >>> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
