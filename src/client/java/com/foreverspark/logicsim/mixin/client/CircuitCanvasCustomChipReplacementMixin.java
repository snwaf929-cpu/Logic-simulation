package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.CustomChipReplacementAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorDocumentSnapshot;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Safe in-place replacement of one placed reusable CUSTOM CHIP while preserving compatible routed wires. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2390)
public abstract class CircuitCanvasCustomChipReplacementMixin implements CustomChipReplacementAccess {
    @Shadow private CircuitDocument document;
    @Shadow @Final private ClientChipLibrary chips;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private NodeKind placementKind;
    @Shadow private String placementChipName;
    @Shadow @Final private Consumer<String> status;

    @Shadow private void recompile() { throw new AssertionError(); }

    @Override
    public boolean logic$replaceSelectedWithPlacementChip() {
        if (document == null || selectedNodeIds.size() != 1 || selectedNodeId == null) {
            status.accept("REPLACE CHIP: select exactly one placed reusable CHIP first");
            return false;
        }
        EditorNode selected;
        try {
            selected = document.node(selectedNodeId);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (selected.kind != NodeKind.CUSTOM_CHIP) {
            status.accept("REPLACE CHIP: selected component is not a reusable CHIP instance");
            return false;
        }
        if (placementKind != NodeKind.CUSTOM_CHIP || placementChipName == null || placementChipName.isBlank()) {
            status.accept("REPLACE CHIP: choose the replacement CHIP in the library, then press Ctrl+R");
            return false;
        }
        String replacement = placementChipName.trim();
        if (chips.find(replacement) == null) {
            status.accept("REPLACE CHIP: saved CHIP not found: " + replacement);
            return false;
        }
        String oldName = selected.chipName == null ? "" : selected.chipName.trim();
        if (replacement.equals(oldName)) {
            status.accept("REPLACE CHIP: " + replacement + " is already selected");
            return true;
        }

        List<PortSpec> oldInputs = NodePorts.inputs(selected, chips);
        List<PortSpec> oldOutputs = NodePorts.outputs(selected, chips);

        CircuitDocument candidate = EditorDocumentSnapshot.copy(document);
        EditorNode candidateNode = candidate.node(selected.id);
        candidateNode.chipName = replacement;
        List<PortSpec> newInputs = NodePorts.inputs(candidateNode, chips);
        List<PortSpec> newOutputs = NodePorts.outputs(candidateNode, chips);
        int candidateRemoved = logic$removeIncompatibleWires(candidate, candidateNode.id, oldInputs, oldOutputs, newInputs, newOutputs);
        try {
            CircuitCompiler.compile(candidate, chips);
        } catch (RuntimeException error) {
            status.accept("REPLACE CHIP blocked: " + logic$message(error));
            return false;
        }

        logic$historyCheckpoint("Replace reusable CHIP");
        selected.chipName = replacement;
        int removed = logic$removeIncompatibleWires(document, selected.id, oldInputs, oldOutputs, newInputs, newOutputs);
        recompile();
        logic$historyCommit();
        placementKind = null;
        placementChipName = null;

        int kept = logic$attachedWireCount(document, selected.id);
        status.accept("Replaced " + (oldName.isBlank() ? "CHIP" : oldName) + " -> " + replacement
                + " | kept " + kept + " compatible routed wire" + (kept == 1 ? "" : "s")
                + (removed > 0 ? ", removed " + removed + " incompatible" : "")
                + (candidateRemoved == removed ? "" : ""));
        return true;
    }

    @Unique
    private static int logic$removeIncompatibleWires(
            CircuitDocument target,
            int nodeId,
            List<PortSpec> oldInputs,
            List<PortSpec> oldOutputs,
            List<PortSpec> newInputs,
            List<PortSpec> newOutputs
    ) {
        int before = target.wires.size();
        target.wires.removeIf(wire -> {
            if (wire.sourceNodeId() == nodeId) {
                return !logic$compatiblePort(oldOutputs, newOutputs, wire.sourcePort());
            }
            if (wire.targetNodeId() == nodeId) {
                return !logic$compatiblePort(oldInputs, newInputs, wire.targetPort());
            }
            return false;
        });
        return before - target.wires.size();
    }

    @Unique
    private static boolean logic$compatiblePort(List<PortSpec> before, List<PortSpec> after, int port) {
        if (port < 0 || port >= before.size() || port >= after.size()) return false;
        PortSpec oldPort = before.get(port);
        PortSpec newPort = after.get(port);
        return oldPort.direction() == newPort.direction() && oldPort.width() == newPort.width();
    }

    @Unique
    private static int logic$attachedWireCount(CircuitDocument target, int nodeId) {
        int count = 0;
        for (WireConnection wire : target.wires) {
            if (wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId) count++;
        }
        return count;
    }

    @Unique private void logic$historyCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$historyCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique private static String logic$message(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? (error == null ? "unknown error" : error.getClass().getSimpleName()) : message;
    }
}
