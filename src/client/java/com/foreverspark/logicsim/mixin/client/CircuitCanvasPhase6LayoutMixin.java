package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorLayoutTools;
import com.foreverspark.logicsim.client.screen.v2.EditorPhase6Access;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Phase 6 layout/locking layer. This is editor-only CAD state: it never adds logic primitives and
 * never recompiles merely because presentation geometry or a lock flag changed.
 */
@Mixin(value = CircuitCanvasWidget.class, priority = 2120)
public abstract class CircuitCanvasPhase6LayoutMixin implements EditorPhase6Access {
    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer draggingNodeId;
    @Shadow @Final private Consumer<String> status;

    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private void alignRoutesForNode(int nodeId) { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }

    @Unique private boolean logic$phase6LockDragWarned;

    @Override
    public boolean logic$toggleSelectedLocks() {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        if (selected.isEmpty()) {
            status.accept("LOCK: select one or more components first");
            return false;
        }

        boolean lock = EditorLayoutTools.lockedCount(selected) != selected.size();
        logic$phase6HistoryCheckpoint(lock ? "Lock objects" : "Unlock objects");
        for (EditorNode node : selected) node.locked = lock;
        logic$phase6HistoryCommit();
        status.accept((lock ? "Locked " : "Unlocked ") + selected.size() + " component" + (selected.size() == 1 ? "" : "s")
                + (lock ? " — locked objects cannot be moved, resized, or deleted" : ""));
        return true;
    }

    @Override
    public boolean logic$alignSelected(EditorLayoutTools.Alignment alignment) {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        if (!logic$phase6CanLayout(selected, 2, "ALIGN")) return false;
        logic$phase6HistoryCheckpoint("Align " + alignment.name().toLowerCase().replace('_', ' '));
        boolean changed = EditorLayoutTools.align(selected, alignment, this::nodeWidth, this::nodeHeight);
        if (changed) logic$phase6RealignRoutes(selected);
        logic$phase6HistoryCommit();
        status.accept(changed
                ? "ALIGN: " + logic$phase6AlignmentLabel(alignment) + " on the editor grid"
                : "ALIGN: selection is already aligned");
        return changed;
    }

    @Override
    public boolean logic$alignSelectedPinRows() {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        if (!logic$phase6CanLayout(selected, 2, "ALIGN PIN ROWS")) return false;
        logic$phase6HistoryCheckpoint("Align pin rows");
        boolean changed = EditorLayoutTools.alignPinRows(selected, this::logic$phase6FirstPinY);
        if (changed) logic$phase6RealignRoutes(selected);
        logic$phase6HistoryCommit();
        status.accept(changed ? "ALIGN PIN ROWS: first connector rows aligned to the grid" : "ALIGN PIN ROWS: already aligned");
        return changed;
    }

    @Override
    public boolean logic$distributeSelected(EditorLayoutTools.Axis axis) {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        if (!logic$phase6CanLayout(selected, 3, "DISTRIBUTE")) return false;
        logic$phase6HistoryCheckpoint("Distribute " + axis.name().toLowerCase());
        boolean changed = EditorLayoutTools.distribute(selected, axis, this::nodeWidth, this::nodeHeight);
        if (changed) logic$phase6RealignRoutes(selected);
        logic$phase6HistoryCommit();
        status.accept(changed
                ? "DISTRIBUTE: equal " + (axis == EditorLayoutTools.Axis.HORIZONTAL ? "horizontal" : "vertical") + " gaps, snapped to grid"
                : "DISTRIBUTE: selection is already evenly distributed");
        return changed;
    }

    @Inject(method = "onClick", at = @At("HEAD"))
    private void logic$phase6ResetDragWarning(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() == 0) logic$phase6LockDragWarned = false;
    }

    @Inject(method = "onDrag", at = @At("HEAD"), cancellable = true)
    private void logic$phase6GuardLockedDrag(MouseButtonEvent event, double dx, double dy, CallbackInfo ci) {
        if (event.button() != 0 || draggingNodeId == null) return;
        List<EditorNode> selected = logic$phase6SelectedNodes();
        int locked = EditorLayoutTools.lockedCount(selected);
        if (locked == 0) return;
        if (!logic$phase6LockDragWarned) {
            status.accept("LOCKED: " + locked + " selected component" + (locked == 1 ? " is" : "s are") + " protected from movement — Ctrl+L unlocks");
            logic$phase6LockDragWarned = true;
        }
        ci.cancel();
    }

    @Inject(method = "onRelease", at = @At("RETURN"))
    private void logic$phase6ReleaseDragWarning(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() == 0) logic$phase6LockDragWarned = false;
    }

    @Inject(method = "deleteSelectionConfirmed", at = @At("HEAD"), cancellable = true)
    private void logic$phase6GuardLockedDelete(CallbackInfo ci) {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        int locked = EditorLayoutTools.lockedCount(selected);
        if (locked == 0) return;
        status.accept("LOCKED: cannot delete this selection while " + locked + " component" + (locked == 1 ? " is" : "s are") + " locked — Ctrl+L unlocks");
        ci.cancel();
    }

    @Inject(method = "changeSelectedWidth", at = @At("HEAD"), cancellable = true)
    private void logic$phase6GuardLockedResize(int direction, CallbackInfo ci) {
        List<EditorNode> selected = logic$phase6SelectedNodes();
        int locked = EditorLayoutTools.lockedCount(selected);
        if (locked == 0) return;
        status.accept("LOCKED: unlock the selected component before resizing it");
        ci.cancel();
    }

    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;drawMarquee(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", shift = At.Shift.BEFORE))
    private void logic$phase6DrawLocks(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        for (EditorNode node : document.nodes) {
            if (!node.locked) continue;
            int left = screenX(node.x);
            int top = screenY(node.y);
            int right = screenX(node.x + nodeWidth(node));
            int bottom = screenY(node.y + nodeHeight(node));
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            graphics.outline(left - 1, top - 1, width + 2, height + 2, 0xFFD3A746);

            int lockX = Math.max(left + 3, right - 13);
            int lockY = top + 5;
            graphics.outline(lockX + 2, lockY, 6, 6, 0xFFFFD56A);
            graphics.fill(lockX, lockY + 4, lockX + 10, lockY + 12, 0xFFE1B64F);
            graphics.fill(lockX + 4, lockY + 7, lockX + 6, lockY + 10, 0xFF3A2D12);
        }
    }

    @Unique
    private boolean logic$phase6CanLayout(List<EditorNode> selected, int minimum, String operation) {
        if (selected.size() < minimum) {
            status.accept(operation + ": select at least " + minimum + " components");
            return false;
        }
        int locked = EditorLayoutTools.lockedCount(selected);
        if (locked > 0) {
            status.accept(operation + ": selection contains " + locked + " locked component" + (locked == 1 ? "" : "s") + " — unlock before changing layout");
            return false;
        }
        return true;
    }

    @Unique
    private List<EditorNode> logic$phase6SelectedNodes() {
        List<EditorNode> selected = new ArrayList<>(selectedNodeIds.size());
        for (Integer id : selectedNodeIds) {
            if (id == null) continue;
            try {
                selected.add(document.node(id));
            } catch (RuntimeException ignored) {
                // Selection can briefly reference an old node while another atomic editor action restores a snapshot.
            }
        }
        return selected;
    }

    @Unique
    private void logic$phase6RealignRoutes(List<EditorNode> selected) {
        for (EditorNode node : selected) alignRoutesForNode(node.id);
    }

    @Unique
    private double logic$phase6FirstPinY(EditorNode node) {
        List<PortSpec> inputs = safeInputs(node);
        if (!inputs.isEmpty()) return logic$phase6InputPinY(node, 0, inputs.size());
        List<PortSpec> outputs = safeOutputs(node);
        if (!outputs.isEmpty()) return logic$phase6OutputPinY(node, 0, outputs.size());
        return EditorGrid.snap(node.y);
    }

    @Unique
    private double logic$phase6InputPinY(EditorNode node, int port, int count) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, count);
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return EditorGrid.snap(y);
    }

    @Unique
    private double logic$phase6OutputPinY(EditorNode node, int port, int count) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, count);
        else y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return EditorGrid.snap(y);
    }

    @Unique
    private String logic$phase6AlignmentLabel(EditorLayoutTools.Alignment alignment) {
        return switch (alignment) {
            case LEFT -> "left edges";
            case RIGHT -> "right edges";
            case TOP -> "top edges";
            case BOTTOM -> "bottom edges";
            case CENTER_X -> "horizontal centers";
            case CENTER_Y -> "vertical centers";
        };
    }

    // Keep private bridge signatures distinct from EditorHistoryAccess public methods on the transformed canvas.
    @Unique
    private void logic$phase6HistoryCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique
    private void logic$phase6HistoryCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }
}
