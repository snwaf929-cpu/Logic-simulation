package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * V2.1C per-instance component body sizing.
 *
 * <p>The electrical model remains untouched: signal widths, port counts and compiler topology do not change. Resizing
 * only changes the editor body rectangle used by pin placement, wire endpoints, hit testing and rendering. Dimensions
 * are snapped to the same six-unit CAD grid as nodes and routes. A zero persisted dimension means automatic sizing.</p>
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2925)
public abstract class CircuitCanvasResizableGeometryV21CMixin {
    @Unique private static final int LOGIC_HANDLE_HALF = 5;

    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private Consumer<String> status;

    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double worldX(double screenX) { throw new AssertionError(); }
    @Shadow private double worldY(double screenY) { throw new AssertionError(); }

    @Unique private Integer logic$pendingResizeNode;
    @Unique private Integer logic$resizingNode;
    @Unique private boolean logic$resizeHistory;

    @Inject(method = "nodeWidth", at = @At("HEAD"), cancellable = true)
    private void logic$manualNodeWidth(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node == null || !logic$resizable(node) || !Double.isFinite(node.editorBodyWidth) || node.editorBodyWidth <= 0.0) return;
        cir.setReturnValue(EditorGrid.snapUp(Math.max(logic$minimumWidth(node), node.editorBodyWidth)));
    }

    @Inject(method = "nodeHeight", at = @At("HEAD"), cancellable = true)
    private void logic$manualNodeHeight(EditorNode node, CallbackInfoReturnable<Double> cir) {
        if (node == null || !logic$resizable(node) || !Double.isFinite(node.editorBodyHeight) || node.editorBodyHeight <= 0.0) return;
        cir.setReturnValue(EditorGrid.snapUp(Math.max(logic$minimumHeight(node), node.editorBodyHeight)));
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$resizeClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        EditorNode node = logic$selectedResizableNode();
        if (node == null || !logic$handleContains(node, event.x(), event.y())) return;

        if (event.button() == 1) {
            if (node.locked) {
                status.accept("RESIZE: " + node.displayName() + " is locked");
                ci.cancel();
                return;
            }
            if (node.editorBodyWidth == 0.0 && node.editorBodyHeight == 0.0) {
                status.accept("RESIZE: " + node.displayName() + " already uses automatic geometry");
                ci.cancel();
                return;
            }
            logic$checkpoint("Reset component size");
            node.editorBodyWidth = 0.0;
            node.editorBodyHeight = 0.0;
            logic$commitHistory();
            status.accept("RESIZE: " + node.displayName() + " restored to automatic body size");
            ci.cancel();
            return;
        }

        if (event.button() != 0) return;
        if (node.locked) {
            status.accept("RESIZE: " + node.displayName() + " is locked");
            ci.cancel();
            return;
        }
        logic$pendingResizeNode = node.id;
        logic$resizingNode = null;
        status.accept("RESIZE: drag the corner on the 6-unit grid; RMB on the handle resets automatic size");
        ci.cancel();
    }

    @Inject(method = "onDrag", at = @At("HEAD"), cancellable = true)
    private void logic$resizeDrag(MouseButtonEvent event, double dx, double dy, CallbackInfo ci) {
        if (event.button() != 0 || (logic$pendingResizeNode == null && logic$resizingNode == null)) return;
        EditorNode node = logic$resizeNode();
        if (node == null) {
            logic$clearResize(false);
            return;
        }

        if (logic$resizingNode == null) {
            logic$checkpoint("Resize component");
            logic$resizingNode = node.id;
            logic$pendingResizeNode = null;
            logic$resizeHistory = true;
        }

        double requestedWidth = EditorGrid.snap(worldX(event.x()) - node.x);
        double requestedHeight = EditorGrid.snap(worldY(event.y()) - node.y);
        node.editorBodyWidth = EditorGrid.snapUp(Math.max(logic$minimumWidth(node), requestedWidth));
        node.editorBodyHeight = EditorGrid.snapUp(Math.max(logic$minimumHeight(node), requestedHeight));
        ci.cancel();
    }

    @Inject(method = "onRelease", at = @At("HEAD"))
    private void logic$resizeRelease(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() != 0) return;
        if (logic$resizeHistory) {
            EditorNode node = logic$resizeNode();
            logic$commitHistory();
            if (node != null) {
                status.accept("RESIZE: " + node.displayName() + " body = "
                        + Math.round(nodeWidth(node)) + " x " + Math.round(nodeHeight(node))
                        + " editor units — electrical behavior unchanged");
            }
        }
        logic$clearResize(false);
    }

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$cancelResize(CallbackInfoReturnable<Boolean> cir) {
        if (logic$pendingResizeNode == null && logic$resizingNode == null) return;
        if (logic$resizeHistory) logic$commitHistory();
        logic$clearResize(false);
        status.accept("RESIZE cancelled");
        cir.setReturnValue(true);
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearResizeOnDocument(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$clearResize(false);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawResizeHandle(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        EditorNode node = logic$selectedResizableNode();
        if (node == null) return;
        int x = screenX(node.x + nodeWidth(node));
        int y = screenY(node.y + nodeHeight(node));
        int accent = node.locked ? 0xFF777777 : 0xFF72B8FF;
        graphics.fill(x - LOGIC_HANDLE_HALF, y - LOGIC_HANDLE_HALF,
                x + LOGIC_HANDLE_HALF + 1, y + LOGIC_HANDLE_HALF + 1, 0xFF11161B);
        graphics.outline(x - LOGIC_HANDLE_HALF - 1, y - LOGIC_HANDLE_HALF - 1,
                LOGIC_HANDLE_HALF * 2 + 3, LOGIC_HANDLE_HALF * 2 + 3, accent);
    }

    @Unique
    private EditorNode logic$selectedResizableNode() {
        if (selectedNodeId == null || selectedNodeIds.size() != 1) return null;
        try {
            EditorNode node = document.node(selectedNodeId);
            return logic$resizable(node) ? node : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private EditorNode logic$resizeNode() {
        Integer id = logic$resizingNode != null ? logic$resizingNode : logic$pendingResizeNode;
        if (id == null) return null;
        try {
            EditorNode node = document.node(id);
            return logic$resizable(node) ? node : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Unique
    private boolean logic$handleContains(EditorNode node, double mouseX, double mouseY) {
        int x = screenX(node.x + nodeWidth(node));
        int y = screenY(node.y + nodeHeight(node));
        return Math.abs(mouseX - x) <= LOGIC_HANDLE_HALF + 3 && Math.abs(mouseY - y) <= LOGIC_HANDLE_HALF + 3;
    }

    @Unique
    private static boolean logic$resizable(EditorNode node) {
        if (node == null || node.kind == null || node.isExternalDevice()) return false;
        // I/O terminals intentionally remain tiny fixed pins; all component/chip bodies are resizable.
        return node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT;
    }

    @Unique
    private double logic$minimumWidth(EditorNode node) {
        return switch (node.kind) {
            case NAND -> 66.0;
            case CONSTANT -> node.randomSource ? 78.0 : 66.0;
            case PROBE -> 66.0;
            case BUS -> node.width <= 1 ? 20.0 : 30.0;
            case SPLITTER, MERGER -> 72.0;
            case BUS_SLICE -> 84.0;
            case NET_LABEL -> 66.0;
            case CUSTOM_CHIP -> 66.0;
            default -> 36.0;
        };
    }

    @Unique
    private double logic$minimumHeight(EditorNode node) {
        return switch (node.kind) {
            case NAND -> 48.0;
            case CONSTANT -> node.randomSource ? 12.0 : 42.0;
            case PROBE -> 42.0;
            case BUS -> 20.0;
            case NET_LABEL -> 24.0;
            case SPLITTER, MERGER, BUS_SLICE -> {
                int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
                yield EditorGrid.snapUp(Math.max(42.0, 34.0 + Math.max(0, ports - 1) * 12.0));
            }
            case CUSTOM_CHIP -> {
                int ports = Math.max(safeInputs(node).size(), safeOutputs(node).size());
                double step = Math.max(EditorGrid.STEP, portStep(node));
                yield EditorGrid.snapUp(Math.max(48.0, 30.0 + Math.max(0, ports - 1) * step));
            }
            default -> 30.0;
        };
    }

    @Unique
    private void logic$checkpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique
    private void logic$commitHistory() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique
    private void logic$clearResize(boolean commit) {
        if (commit && logic$resizeHistory) logic$commitHistory();
        logic$pendingResizeNode = null;
        logic$resizingNode = null;
        logic$resizeHistory = false;
    }
}
