package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Width + lane grouping picker for BUS, SPLITTER and MERGER. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1100)
public abstract class CircuitCanvasRoutingWidthMixin {
    @Unique private static final int[] CONNECTOR_WIDTHS = {1, 2, 4, 8, 16, 32, 64};
    @Unique private static final int[] PACKED_WIDTHS = {2, 4, 8, 16, 32, 64};
    @Unique private static final int[] LANE_WIDTHS = {1, 2, 4, 8, 16, 32, 64};

    @Shadow private CircuitDocument document;
    @Shadow private NodeKind placementKind;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private Consumer<String> status;
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }

    @Unique private boolean logic$pickerOpen;
    @Unique private int logic$width = 1;
    @Unique private int logic$laneWidth = 1;
    @Unique private int logic$lastConnectorWidth = 1;
    @Unique private int logic$lastSplitterWidth = 8;
    @Unique private int logic$lastMergerWidth = 8;
    @Unique private int logic$lastSplitterLaneWidth = 1;
    @Unique private int logic$lastMergerLaneWidth = 1;
    @Unique private NodeKind logic$beforePlacement;
    @Unique private Integer logic$editingNodeId;

    @Inject(method = "setPlacement", at = @At("TAIL"))
    private void logic$setPlacement(NodeKind kind, CallbackInfo ci) {
        logic$editingNodeId = null;
        logic$pickerOpen = logic$isRouting(kind);
        if (!logic$pickerOpen) return;

        logic$width = switch (kind) {
            case BUS -> logic$lastConnectorWidth;
            case SPLITTER -> logic$lastSplitterWidth;
            case MERGER -> logic$lastMergerWidth;
            default -> 1;
        };
        logic$laneWidth = switch (kind) {
            case SPLITTER -> logic$validLane(logic$lastSplitterLaneWidth, logic$width);
            case MERGER -> logic$validLane(logic$lastMergerLaneWidth, logic$width);
            default -> 1;
        };
        status.accept(logic$helpText(kind));
    }

    @Inject(method = "cancelPlacement", at = @At("TAIL"))
    private void logic$cancel(CallbackInfo ci) {
        logic$pickerOpen = false;
        logic$editingNodeId = null;
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$click(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;

        if (logic$pickerOpen) {
            int busWidth = logic$busButtonAt(event.x(), event.y());
            if (busWidth > 0) {
                logic$width = busWidth;
                logic$laneWidth = logic$validLane(logic$laneWidth, logic$width);
                logic$rememberWidth(logic$activeKind(), busWidth);
                if (logic$activeKind() == NodeKind.BUS) {
                    logic$pickerOpen = false;
                    status.accept("WIRE / BUS = " + busWidth + " bit — click the canvas to place it");
                } else {
                    status.accept(logic$preview(logic$activeKind()) + " — now choose lane width");
                }
                ci.cancel();
                return;
            }

            if (logic$activeKind() == NodeKind.SPLITTER || logic$activeKind() == NodeKind.MERGER) {
                int laneWidth = logic$laneButtonAt(event.x(), event.y());
                if (laneWidth > 0 && laneWidth <= logic$width && logic$width % laneWidth == 0) {
                    logic$laneWidth = laneWidth;
                    logic$rememberLaneWidth(logic$activeKind(), laneWidth);
                    if (logic$editingNodeId != null) {
                        logic$applyExistingNodeConfiguration();
                    } else {
                        logic$pickerOpen = false;
                        status.accept(logic$preview(logic$activeKind()) + " — click the canvas to place it");
                    }
                    ci.cancel();
                    return;
                }
            }

            status.accept(logic$helpText(logic$activeKind()));
            ci.cancel();
            return;
        }

        // Double-click an existing splitter/merger to reconfigure it without deleting/replacing the component.
        if (doubleClick && placementKind == null) {
            EditorNode node = nodeAt(event.x(), event.y());
            if (node != null && (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER)) {
                logic$editingNodeId = node.id;
                logic$width = node.width;
                logic$laneWidth = node.normalizedLaneWidth();
                logic$pickerOpen = true;
                status.accept("Edit " + logic$preview(node.kind) + " — choose bus width and lane width");
                ci.cancel();
                return;
            }
        }

        logic$beforePlacement = placementKind;
    }

    @Inject(method = "onClick", at = @At("RETURN"))
    private void logic$afterClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        NodeKind before = logic$beforePlacement;
        logic$beforePlacement = null;
        if (event.button() != 0 || !logic$isRouting(before) || placementKind != null || selectedNodeId == null) return;
        try {
            EditorNode node = document.node(selectedNodeId);
            if (node.kind != before) return;
            node.width = logic$width;
            node.laneWidth = before == NodeKind.SPLITTER || before == NodeKind.MERGER ? logic$laneWidth : 1;
            recompile();
            status.accept("Placed " + (before == NodeKind.BUS ? node.width + "-bit BUS" : logic$preview(before)));
        } catch (RuntimeException exception) {
            status.accept("ERROR: " + exception.getMessage());
        }
    }

    @Unique
    private void logic$applyExistingNodeConfiguration() {
        if (logic$editingNodeId == null) return;
        try {
            EditorNode node = document.node(logic$editingNodeId);
            if (node.kind != NodeKind.SPLITTER && node.kind != NodeKind.MERGER) return;
            boolean changed = node.width != logic$width || node.normalizedLaneWidth() != logic$laneWidth;
            int removedConnections = changed ? document.connectionCount(node.id) : 0;
            if (changed) {
                node.width = logic$width;
                node.laneWidth = logic$laneWidth;
                document.removeWiresForNode(node.id);
                recompile();
            }
            logic$pickerOpen = false;
            logic$editingNodeId = null;
            status.accept(logic$preview(node.kind)
                    + (removedConnections > 0 ? " — " + removedConnections + " old connection(s) removed because port widths changed" : ""));
        } catch (RuntimeException exception) {
            logic$pickerOpen = false;
            logic$editingNodeId = null;
            status.accept("ERROR: " + exception.getMessage());
        }
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!logic$pickerOpen || !logic$isRouting(logic$activeKind())) return;

        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        NodeKind kind = logic$activeKind();
        int[] busOptions = logic$options(kind);
        int panelWidth = Math.max(logic$panelWidth(busOptions), kind == NodeKind.BUS ? 0 : logic$panelWidth(LANE_WIDTHS));
        int panelHeight = kind == NodeKind.BUS ? 47 : 78;
        int x = self.getX() + 10;
        int y = self.getY() + 25;
        var font = net.minecraft.client.Minecraft.getInstance().font;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xF0161B21);
        graphics.outline(x, y, panelWidth, panelHeight, 0xFF4B5968);
        graphics.text(font, logic$title(kind), x + 7, y + 6, 0xFFE6ECF3, false);

        int bx = x + 7;
        int by = y + 21;
        graphics.text(font, "BUS", bx, by + 5, 0xFF93A6BA, false);
        bx += 30;
        for (int width : busOptions) {
            bx = logic$drawButton(graphics, font, mouseX, mouseY, bx, by, width, width == logic$width, true);
        }

        if (kind != NodeKind.BUS) {
            bx = x + 7;
            by = y + 43;
            graphics.text(font, kind == NodeKind.SPLITTER ? "OUT" : "IN", bx, by + 5, 0xFF93A6BA, false);
            bx += 30;
            for (int lane : LANE_WIDTHS) {
                if (lane > logic$width || logic$width % lane != 0) continue;
                bx = logic$drawButton(graphics, font, mouseX, mouseY, bx, by, lane, lane == logic$laneWidth, false);
            }
            graphics.text(font, logic$preview(kind), x + 7, y + 65, 0xFF7FC7FF, false);
        }
    }

    @Unique
    private int logic$drawButton(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                                 int mouseX, int mouseY, int bx, int by, int width,
                                 boolean active, boolean busRow) {
        int bw = logic$buttonWidth(width);
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 17;
        graphics.fill(bx, by, bx + bw, by + 17, active ? 0xFF294866 : hover ? 0xFF29313A : 0xFF20262D);
        graphics.outline(bx, by, bw, 17, active ? 0xFF6CA9FF : hover ? 0xFF66788A : 0xFF3E4955);
        String text = Integer.toString(width);
        graphics.text(font, text, bx + (bw - font.width(text)) / 2, by + 5, busRow ? 0xFFF0F4F8 : 0xFFD9F0FF, false);
        return bx + bw + 5;
    }

    @Unique
    private int logic$busButtonAt(double mouseX, double mouseY) {
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int x = self.getX() + 47;
        int y = self.getY() + 46;
        for (int width : logic$options(logic$activeKind())) {
            int bw = logic$buttonWidth(width);
            if (mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + 17) return width;
            x += bw + 5;
        }
        return -1;
    }

    @Unique
    private int logic$laneButtonAt(double mouseX, double mouseY) {
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int x = self.getX() + 47;
        int y = self.getY() + 68;
        for (int lane : LANE_WIDTHS) {
            if (lane > logic$width || logic$width % lane != 0) continue;
            int bw = logic$buttonWidth(lane);
            if (mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + 17) return lane;
            x += bw + 5;
        }
        return -1;
    }

    @Unique private NodeKind logic$activeKind() {
        if (logic$editingNodeId != null) {
            try { return document.node(logic$editingNodeId).kind; }
            catch (RuntimeException ignored) { return null; }
        }
        return placementKind;
    }

    @Unique private void logic$rememberWidth(NodeKind kind, int width) {
        if (kind == NodeKind.BUS) logic$lastConnectorWidth = width;
        if (kind == NodeKind.SPLITTER) logic$lastSplitterWidth = width;
        if (kind == NodeKind.MERGER) logic$lastMergerWidth = width;
    }

    @Unique private void logic$rememberLaneWidth(NodeKind kind, int width) {
        if (kind == NodeKind.SPLITTER) logic$lastSplitterLaneWidth = width;
        if (kind == NodeKind.MERGER) logic$lastMergerLaneWidth = width;
    }

    @Unique private static int logic$validLane(int laneWidth, int busWidth) {
        if (laneWidth <= 0 || laneWidth > busWidth || busWidth % laneWidth != 0 || (laneWidth & (laneWidth - 1)) != 0) return 1;
        return laneWidth;
    }

    @Unique private static int[] logic$options(NodeKind kind) {
        return kind == NodeKind.BUS ? CONNECTOR_WIDTHS : PACKED_WIDTHS;
    }

    @Unique private static int logic$buttonWidth(int width) { return width >= 10 ? 29 : 26; }

    @Unique private static int logic$panelWidth(int[] options) {
        int width = 44;
        for (int option : options) width += logic$buttonWidth(option) + 5;
        return width;
    }

    @Unique private static boolean logic$isRouting(NodeKind kind) {
        return kind == NodeKind.BUS || kind == NodeKind.SPLITTER || kind == NodeKind.MERGER;
    }

    @Unique private String logic$preview(NodeKind kind) {
        int count = Math.max(1, logic$width / Math.max(1, logic$laneWidth));
        if (kind == NodeKind.SPLITTER) return logic$width + "-bit -> " + count + " x " + logic$laneWidth + "-bit outputs";
        if (kind == NodeKind.MERGER) return count + " x " + logic$laneWidth + "-bit inputs -> " + logic$width + "-bit";
        return logic$width + "-bit BUS";
    }

    @Unique private String logic$helpText(NodeKind kind) {
        if (kind == NodeKind.BUS) return "Choose BUS width: 1 / 2 / 4 / 8 / 16 / 32 / 64 bits";
        return "Choose total BUS width, then choose each " + (kind == NodeKind.SPLITTER ? "output" : "input")
                + " lane width. Example: 64 with lane 32 = two 32-bit ports; lane 2 = thirty-two 2-bit ports.";
    }

    @Unique private static String logic$title(NodeKind kind) {
        if (kind == NodeKind.SPLITTER) return "BUS -> GROUPED OUTPUTS";
        if (kind == NodeKind.MERGER) return "GROUPED INPUTS -> BUS";
        return "WIRE / BUS CONNECTOR WIDTH";
    }
}
