package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import com.foreverspark.logicsim.client.screen.Phase2NodeConfigScreen;
import com.foreverspark.logicsim.client.screen.v2.CanvasPhase2ConfigAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.NumericValueCodec;
import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.Map;
import java.util.function.Consumer;

/** Exact configuration for arbitrary widths, bus values, BUS_SLICE, and NET_LABEL. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2250)
public abstract class CircuitCanvasPhase2ConfigMixin implements CanvasPhase2ConfigAccess {
    @Shadow private CircuitDocument document;
    @Shadow private String runtimeScopePath;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow @Final private Map<Integer, Long> inputStates;
    @Shadow @Final private Consumer<String> status;
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$doubleClickConfigure(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 || !doubleClick) return;
        EditorNode node = nodeAt(event.x(), event.y());
        if (node == null || !logic$isConfigurable(node)) return;
        logic$open(node, EditorScreenContext.current());
        ci.cancel();
    }

    @Override
    public boolean logic$configureSelected(Screen parent) {
        if (selectedNodeIds.size() != 1 || selectedNodeId == null) {
            status.accept(selectedNodeIds.isEmpty() ? "Select one configurable component first" : "Configure requires exactly one component");
            return false;
        }
        EditorNode node;
        try {
            node = document.node(selectedNodeId);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!logic$isConfigurable(node)) {
            status.accept(node.displayName() + " has no Phase 2 value/width configuration");
            return false;
        }
        logic$open(node, parent);
        return true;
    }

    @Unique
    private boolean logic$isConfigurable(EditorNode node) {
        if (node == null || node.kind == NodeKind.NAND || node.kind == NodeKind.CUSTOM_CHIP) return false;
        if (node.kind == NodeKind.CONSTANT && (node.clockSource || node.randomSource)) return false;
        return true;
    }

    @Unique
    private void logic$open(EditorNode node, Screen parent) {
        if (node.kind == NodeKind.INPUT || node.kind == NodeKind.CONSTANT) {
            long value = node.kind == NodeKind.INPUT
                    ? inputStates.getOrDefault(node.id, node.inputDefaultValue)
                    : node.constantValue;
            Minecraft.getInstance().setScreen(Phase2NodeConfigScreen.value(
                    parent,
                    node.kind == NodeKind.INPUT ? "INPUT VALUE" : "CONSTANT VALUE",
                    node.width,
                    value,
                    (width, nextValue) -> {
                        logic$checkpoint(node.kind == NodeKind.INPUT ? "Configure input" : "Configure constant");
                        boolean widthChanged = node.width != width;
                        node.width = width;
                        long masked = nextValue & NumericValueCodec.mask(width);
                        if (node.kind == NodeKind.INPUT) {
                            node.inputDefaultValue = masked;
                            if (CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) inputStates.put(node.id, masked);
                        } else {
                            node.constantValue = masked;
                        }
                        int removed = widthChanged ? logic$removeAttachedWires(node.id) : 0;
                        recompile();
                        logic$commit();
                        status.accept(node.displayName() + " = " + NumericValueCodec.hex(masked, width)
                                + (removed > 0 ? "; removed " + removed + " width-dependent connection" + (removed == 1 ? "" : "s") : ""));
                    }
            ));
            return;
        }

        if (node.kind == NodeKind.BUS_SLICE) {
            List<BusSliceOutput> slices = new ArrayList<>();
            for (BusSliceOutput slice : node.normalizedSlices()) slices.add(slice.copy());
            Minecraft.getInstance().setScreen(Phase2NodeConfigScreen.slice(parent, node.width, slices, (width, nextSlices) -> {
                logic$checkpoint("Configure bus slice");
                node.width = width;
                node.slices = new ArrayList<>();
                for (BusSliceOutput slice : nextSlices) node.slices.add(slice.copy());
                node.normalizedSlices();
                int removed = logic$removeAttachedWires(node.id);
                recompile();
                logic$commit();
                status.accept("BUS SLICE = " + width + " bit -> " + node.slices.size() + " range" + (node.slices.size() == 1 ? "" : "s")
                        + (removed > 0 ? "; reconnect changed ports" : ""));
            }));
            return;
        }

        if (node.kind == NodeKind.NET_LABEL) {
            Minecraft.getInstance().setScreen(Phase2NodeConfigScreen.net(parent, node.label, node.width, (name, width) -> {
                logic$checkpoint("Configure net label");
                boolean electricalChange = node.width != width || !name.equals(node.label);
                node.label = name;
                node.width = width;
                int removed = electricalChange ? logic$removeAttachedWires(node.id) : 0;
                recompile();
                logic$commit();
                status.accept("NET " + name + " [" + width + "]" + (removed > 0 ? " — reconnect changed local port" : ""));
            }));
            return;
        }

        int lane = (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) ? node.normalizedLaneWidth() : 1;
        Minecraft.getInstance().setScreen(Phase2NodeConfigScreen.width(parent, node.displayName(), node.width, lane, (width, laneWidth) -> {
            logic$checkpoint("Configure width");
            boolean changed = node.width != width || ((node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) && node.laneWidth != laneWidth);
            node.width = width;
            if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) node.laneWidth = laneWidth;
            int removed = changed ? logic$removeAttachedWires(node.id) : 0;
            recompile();
            logic$commit();
            status.accept(node.displayName() + " configured" + (removed > 0 ? "; reconnect " + removed + " changed connection" + (removed == 1 ? "" : "s") : ""));
        }));
    }

    @Unique
    private int logic$removeAttachedWires(int nodeId) {
        int before = document.wires.size();
        document.wires.removeIf(wire -> wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId);
        return before - document.wires.size();
    }

    @Unique private void logic$checkpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$commit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }
}
