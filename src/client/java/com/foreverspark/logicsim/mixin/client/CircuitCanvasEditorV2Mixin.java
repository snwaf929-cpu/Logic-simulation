package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorDocumentSnapshot;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistory;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorPinGeometry;
import com.foreverspark.logicsim.client.screen.v2.EditorPinSelectionAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Central selection, pin geometry, batch wiring, floating-warning, and history foundation for Editor V2. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2000)
public abstract class CircuitCanvasEditorV2Mixin implements EditorHistoryAccess, EditorPinSelectionAccess {
    @Shadow private CircuitDocument document;
    @Shadow private CircuitDocument runtimeRootDocument;
    @Shadow private String runtimeScopePath;
    @Shadow @Final private ClientChipLibrary chips;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;
    @Shadow private Integer draggingNodeId;
    @Shadow private double nodeDragDistance;
    @Shadow private boolean nodeActuallyMoved;
    @Shadow private boolean marqueePending;
    @Shadow private boolean marqueeActive;
    @Shadow private double marqueeStartX;
    @Shadow private double marqueeStartY;
    @Shadow private double marqueeCurrentX;
    @Shadow private double marqueeCurrentY;
    @Shadow @Final private Map<Integer, Long> inputStates;
    @Shadow @Final private Consumer<String> status;

    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private WireConnection wireAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void beginMarquee(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void setSelectedNodes(Iterable<Integer> nodeIds) { throw new AssertionError(); }
    @Shadow private void updatePrimarySelection() { throw new AssertionError(); }
    @Shadow private void clearSelection() { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }
    @Shadow private int portDisplayColor(EditorNode node, int port, PortSpec spec, boolean input) { throw new AssertionError(); }
    @Shadow private boolean validTarget(boolean input) { throw new AssertionError(); }
    @Shadow private void drawNode(GuiGraphicsExtractor graphics, EditorNode node) { throw new AssertionError(); }
    @Shadow private void drawWirePreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) { throw new AssertionError(); }

    @Unique private final EditorHistory logic$history = new EditorHistory();
    @Unique private final LinkedHashSet<LogicPinKey> logic$selectedPins = new LinkedHashSet<>();
    @Unique private final LinkedHashSet<LogicPinKey> logic$errorPins = new LinkedHashSet<>();
    @Unique private LogicSelectionMode logic$selectionMode = LogicSelectionMode.COMPONENT;
    @Unique private LogicSelectionMode logic$marqueeMode = LogicSelectionMode.COMPONENT;
    @Unique private boolean logic$marqueeAdditive;

    /* ----------------------------- click / selection ----------------------------- */

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$selectionClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;

        logic$history.checkpoint("Canvas edit", document);
        logic$errorPins.clear();
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        LogicPinHit exactPin = logic$pinAt(event.x(), event.y(), true);

        if (!alt && exactPin == null && logic$pinAt(event.x(), event.y(), false) != null) {
            ci.cancel();
            return;
        }

        if (alt) {
            logic$enterPinMode(shift);
            logic$marqueeMode = LogicSelectionMode.PIN;
            logic$marqueeAdditive = shift;
            if (exactPin != null) {
                if (!shift) logic$selectedPins.clear();
                logic$selectedPins.add(exactPin.key());
                selectedWire = null;
                wireEditMode = false;
                status.accept(logic$pinSelectionStatus());
            } else {
                beginMarquee(event.x(), event.y());
            }
            ci.cancel();
            return;
        }

        logic$enterComponentMode();
        logic$marqueeMode = LogicSelectionMode.COMPONENT;
        logic$marqueeAdditive = shift;
        if (exactPin != null) return;

        if (shift) {
            EditorNode node = nodeAt(event.x(), event.y());
            if (node != null) {
                selectedNodeIds.add(node.id);
                updatePrimarySelection();
                selectedWire = null;
                wireEditMode = false;
                draggingNodeId = node.id;
                nodeDragDistance = 0.0;
                nodeActuallyMoved = false;
                marqueePending = false;
                marqueeActive = false;
                status.accept(selectedNodeIds.size() + " component" + (selectedNodeIds.size() == 1 ? "" : "s") + " selected");
                ci.cancel();
                return;
            }
            if (wireAt(event.x(), event.y()) == null) {
                beginMarquee(event.x(), event.y());
                ci.cancel();
            }
        }
    }

    @Inject(method = "onRelease", at = @At("HEAD"))
    private void logic$finishZeroAreaMarquee(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() == 0 && marqueePending && !marqueeActive) marqueeActive = true;
    }

    @Inject(method = "onRelease", at = @At("RETURN"))
    private void logic$commitPointerEdit(MouseButtonEvent event, CallbackInfo ci) {
        if (event.button() == 0) logic$history.commit(document);
    }

    @Inject(method = "finishMarqueeSelection", at = @At("HEAD"), cancellable = true)
    private void logic$fullContainmentMarquee(CallbackInfo ci) {
        double left = Math.min(marqueeStartX, marqueeCurrentX);
        double right = Math.max(marqueeStartX, marqueeCurrentX);
        double top = Math.min(marqueeStartY, marqueeCurrentY);
        double bottom = Math.max(marqueeStartY, marqueeCurrentY);

        if (logic$marqueeMode == LogicSelectionMode.PIN) {
            LinkedHashSet<LogicPinKey> hits = new LinkedHashSet<>();
            for (EditorNode node : document.nodes) {
                logic$collectPinsInMarquee(node, safeInputs(node), true, left, right, top, bottom, hits);
                logic$collectPinsInMarquee(node, safeOutputs(node), false, left, right, top, bottom, hits);
            }
            if (!logic$marqueeAdditive) logic$selectedPins.clear();
            logic$selectedPins.addAll(hits);
            selectedNodeIds.clear();
            selectedNodeId = null;
            selectedWire = null;
            logic$selectionMode = LogicSelectionMode.PIN;
            status.accept(logic$selectedPins.isEmpty() ? "No pins selected" : logic$pinSelectionStatus());
            ci.cancel();
            return;
        }

        LinkedHashSet<Integer> hits = logic$marqueeAdditive ? new LinkedHashSet<>(selectedNodeIds) : new LinkedHashSet<>();
        int newlyContained = 0;
        for (EditorNode node : document.nodes) {
            double nx = screenX(node.x);
            double ny = screenY(node.y);
            double nr = screenX(node.x + nodeWidth(node));
            double nb = screenY(node.y + nodeHeight(node));
            boolean contained = nx >= left && nr <= right && ny >= top && nb <= bottom;
            if (contained && hits.add(node.id)) newlyContained++;
        }
        setSelectedNodes(hits);
        logic$selectedPins.clear();
        logic$selectionMode = LogicSelectionMode.COMPONENT;
        status.accept(hits.isEmpty() ? "No components selected"
                : hits.size() + " component" + (hits.size() == 1 ? "" : "s") + " selected"
                + (logic$marqueeAdditive && newlyContained > 0 ? " (added " + newlyContained + ")" : ""));
        ci.cancel();
    }

    @Inject(method = "selectAllNodes", at = @At("HEAD"))
    private void logic$selectAllUsesComponentMode(CallbackInfo ci) {
        logic$enterComponentMode();
        logic$selectedPins.clear();
        logic$errorPins.clear();
    }

    /* ----------------------------- batch pin connection ----------------------------- */

    @Override
    public boolean logic$hasPinSelection() {
        return logic$selectionMode == LogicSelectionMode.PIN && !logic$selectedPins.isEmpty();
    }

    @Override
    public boolean logic$batchConnectSelectedPins() {
        if (!logic$hasPinSelection()) {
            status.accept("Alt-select output and input pins first");
            return false;
        }
        logic$errorPins.clear();
        List<LogicPinHit> outputs = new ArrayList<>();
        List<LogicPinHit> inputs = new ArrayList<>();
        for (LogicPinKey key : logic$selectedPins) {
            LogicPinHit hit = logic$resolvePin(key);
            if (hit == null) continue;
            (key.input() ? inputs : outputs).add(hit);
        }
        Comparator<LogicPinHit> topToBottom = Comparator
                .comparingDouble((LogicPinHit hit) -> hit.point().y())
                .thenComparingDouble(hit -> hit.point().x())
                .thenComparingInt(hit -> hit.key().nodeId())
                .thenComparingInt(hit -> hit.key().port());
        outputs.sort(topToBottom);
        inputs.sort(topToBottom);

        if (outputs.isEmpty() || inputs.isEmpty()) {
            logic$errorPins.addAll(logic$selectedPins);
            status.accept("BATCH CONNECT: select at least one OUTPUT pin and one INPUT pin");
            return false;
        }
        if (outputs.size() != inputs.size()) {
            logic$errorPins.addAll(logic$selectedPins);
            status.accept("BATCH CONNECT: pin count mismatch — " + outputs.size() + " outputs vs " + inputs.size() + " inputs");
            return false;
        }

        CircuitDocument candidate = EditorDocumentSnapshot.copy(document);
        for (int i = 0; i < outputs.size(); i++) {
            LogicPinHit out = outputs.get(i);
            LogicPinHit in = inputs.get(i);
            if (out.spec().width() != in.spec().width()) {
                logic$errorPins.add(out.key());
                logic$errorPins.add(in.key());
                status.accept(logic$pairLabel(out, in) + " — WIDTH MISMATCH");
                return false;
            }
            if (logic$hasIncoming(in.key().nodeId(), in.key().port())) {
                logic$errorPins.add(in.key());
                status.accept(logic$pairLabel(out, in) + " — DESTINATION ALREADY CONNECTED");
                return false;
            }
            candidate.connect(out.key().nodeId(), out.key().port(), in.key().nodeId(), in.key().port());
        }

        try {
            CircuitCompiler.compile(candidate, chips);
        } catch (RuntimeException exception) {
            logic$errorPins.addAll(logic$selectedPins);
            status.accept("BATCH CONNECT REJECTED: " + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            return false;
        }

        logic$history.checkpoint("Batch connect", document);
        for (int i = 0; i < outputs.size(); i++) {
            LogicPinHit out = outputs.get(i);
            LogicPinHit in = inputs.get(i);
            document.connect(out.key().nodeId(), out.key().port(), in.key().nodeId(), in.key().port());
        }
        recompile();
        logic$history.commit(document);
        status.accept("BATCH CONNECT: created " + outputs.size() + " top-to-bottom connection" + (outputs.size() == 1 ? "" : "s") + " atomically");
        return true;
    }

    @Unique private boolean logic$hasIncoming(int nodeId, int port) {
        for (WireConnection wire : document.wires) if (wire.targetNodeId() == nodeId && wire.targetPort() == port) return true;
        return false;
    }

    @Unique private String logic$pairLabel(LogicPinHit out, LogicPinHit in) {
        return logic$pinName(out) + " [" + out.spec().width() + "] -> " + logic$pinName(in) + " [" + in.spec().width() + "]";
    }

    @Unique private String logic$pinName(LogicPinHit hit) {
        String name = hit.spec().name() == null || hit.spec().name().isBlank() ? (hit.key().input() ? "IN" : "OUT") : hit.spec().name();
        try {
            return document.node(hit.key().nodeId()).displayName() + "." + name;
        } catch (RuntimeException ignored) {
            return name;
        }
    }

    @Unique private String logic$pinSelectionStatus() {
        int inputs = 0, outputs = 0;
        for (LogicPinKey key : logic$selectedPins) if (key.input()) inputs++; else outputs++;
        String base = logic$selectedPins.size() + " pin" + (logic$selectedPins.size() == 1 ? "" : "s") + " selected";
        if (inputs > 0 && outputs > 0) return base + " — Enter batch-connects top-to-bottom (" + outputs + " -> " + inputs + ")";
        return base + " — Alt+Shift adds more pins";
    }

    /* ----------------------------- pin geometry / z order ----------------------------- */

    @ModifyConstant(method = "outputPortAt", constant = @Constant(doubleValue = 8.0))
    private double logic$equalizeLegacyPortRadius(double original) { return 9.0; }

    @Inject(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = "Lcom/foreverspark/logicsim/client/screen/CircuitCanvasWidget;drawMarquee(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", shift = At.Shift.BEFORE))
    private void logic$editorV2Overlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        for (EditorNode node : document.nodes) if (selectedNodeIds.contains(node.id)) drawNode(graphics, node);

        for (EditorNode node : document.nodes) {
            List<PortSpec> inputs = safeInputs(node);
            for (int port = 0; port < inputs.size(); port++) {
                LogicPinPoint point = logic$inputPoint(node, port);
                PortSpec spec = inputs.get(port);
                LogicPinKey key = new LogicPinKey(node.id, port, true);
                logic$drawPin(graphics, point, spec.width(), portDisplayColor(node, port, spec, true), logic$selectedPins.contains(key), logic$errorPins.contains(key), logic$isFloating(node, port));
            }
            List<PortSpec> outputs = safeOutputs(node);
            for (int port = 0; port < outputs.size(); port++) {
                LogicPinPoint point = logic$outputPoint(node, port);
                PortSpec spec = outputs.get(port);
                LogicPinKey key = new LogicPinKey(node.id, port, false);
                logic$drawPin(graphics, point, spec.width(), portDisplayColor(node, port, spec, false), logic$selectedPins.contains(key), logic$errorPins.contains(key), false);
            }
        }
        drawWirePreview(graphics, mouseX, mouseY);
    }

    @Unique
    private void logic$drawPin(GuiGraphicsExtractor graphics, LogicPinPoint point, int width, int color, boolean selected, boolean error, boolean floating) {
        int x = screenX(point.x());
        int y = screenY(point.y());
        EditorPinGeometry.draw(graphics, x, y, width, color);
        int half = EditorPinGeometry.halfSize(width);
        if (floating) graphics.outline(x - half - 3, y - half - 3, half * 2 + 7, half * 2 + 7, 0xFFFFB347);
        if (selected) graphics.outline(x - half - 2, y - half - 2, half * 2 + 5, half * 2 + 5, 0xFFFFFFFF);
        if (error) graphics.outline(x - half - 4, y - half - 4, half * 2 + 9, half * 2 + 9, 0xFFFF4D4D);
    }

    @Unique private boolean logic$isFloating(EditorNode node, int port) {
        if (node.kind == NodeKind.NET_LABEL) {
            String name = node.label == null ? "" : node.label.trim();
            for (EditorNode candidate : document.nodes) {
                if (candidate.kind != NodeKind.NET_LABEL || candidate.label == null || !candidate.label.trim().equalsIgnoreCase(name)) continue;
                if (logic$hasIncoming(candidate.id, 0)) return false;
            }
            return true;
        }
        return !logic$hasIncoming(node.id, port);
    }

    @Unique
    private LogicPinHit logic$pinAt(double mouseX, double mouseY, boolean exact) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeOutputs(node);
            for (int port = 0; port < ports.size(); port++) {
                LogicPinPoint point = logic$outputPoint(node, port);
                if (logic$pinContains(mouseX, mouseY, point, ports.get(port).width(), exact)) return new LogicPinHit(new LogicPinKey(node.id, port, false), ports.get(port), point);
            }
        }
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeInputs(node);
            for (int port = 0; port < ports.size(); port++) {
                LogicPinPoint point = logic$inputPoint(node, port);
                if (logic$pinContains(mouseX, mouseY, point, ports.get(port).width(), exact)) return new LogicPinHit(new LogicPinKey(node.id, port, true), ports.get(port), point);
            }
        }
        return null;
    }

    @Unique private LogicPinHit logic$resolvePin(LogicPinKey key) {
        try {
            EditorNode node = document.node(key.nodeId());
            List<PortSpec> ports = key.input() ? safeInputs(node) : safeOutputs(node);
            if (key.port() < 0 || key.port() >= ports.size()) return null;
            return new LogicPinHit(key, ports.get(key.port()), key.input() ? logic$inputPoint(node, key.port()) : logic$outputPoint(node, key.port()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Unique private boolean logic$pinContains(double mouseX, double mouseY, LogicPinPoint point, int width, boolean exact) {
        double dx = mouseX - screenX(point.x());
        double dy = mouseY - screenY(point.y());
        if (!exact) return dx * dx + dy * dy <= 81.0;
        return EditorPinGeometry.contains(dx, dy, width);
    }

    @Unique
    private LogicPinPoint logic$inputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeInputs(node).size());
        else if (node.kind == NodeKind.CONSTANT && node.randomSource) y = node.y + nodeHeight(node) * 0.5;
        else y = switch (node.kind) {
            case OUTPUT, PROBE, BUS, SPLITTER, BUS_SLICE, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPinPoint(EditorGrid.snap(node.x), EditorGrid.snap(y));
    }

    @Unique
    private LogicPinPoint logic$outputPoint(EditorNode node, int port) {
        double y;
        if (node.kind == NodeKind.CUSTOM_CHIP) y = centeredPortY(node, port, safeOutputs(node).size());
        else y = switch (node.kind) {
            case INPUT, NAND, CONSTANT, BUS, MERGER, NET_LABEL -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPinPoint(EditorGrid.snap(node.x + nodeWidth(node)), EditorGrid.snap(y));
    }

    @Unique
    private void logic$collectPinsInMarquee(EditorNode node, List<PortSpec> ports, boolean input, double left, double right, double top, double bottom, Set<LogicPinKey> hits) {
        for (int port = 0; port < ports.size(); port++) {
            LogicPinPoint point = input ? logic$inputPoint(node, port) : logic$outputPoint(node, port);
            int x = screenX(point.x());
            int y = screenY(point.y());
            if (x >= left && x <= right && y >= top && y <= bottom) hits.add(new LogicPinKey(node.id, port, input));
        }
    }

    @Unique private void logic$enterPinMode(boolean additive) {
        if (logic$selectionMode != LogicSelectionMode.PIN) logic$selectedPins.clear();
        if (!additive) logic$selectedPins.clear();
        selectedNodeIds.clear();
        selectedNodeId = null;
        selectedWire = null;
        wireEditMode = false;
        logic$selectionMode = LogicSelectionMode.PIN;
    }

    @Unique private void logic$enterComponentMode() {
        if (logic$selectionMode == LogicSelectionMode.PIN) logic$selectedPins.clear();
        logic$selectionMode = LogicSelectionMode.COMPONENT;
    }

    /* ----------------------------- undo / redo ----------------------------- */

    @Override public void logic$checkpoint(String label) { logic$history.checkpoint(label, document); }
    @Override public void logic$commitHistory() { logic$history.commit(document); }

    @Override
    public boolean logic$undo() {
        EditorHistory.Result result = logic$history.undo(document);
        if (result == null) { status.accept("Nothing to undo"); return false; }
        logic$restore(result.document());
        status.accept("Undo: " + result.label());
        return true;
    }

    @Override
    public boolean logic$redo() {
        EditorHistory.Result result = logic$history.redo(document);
        if (result == null) { status.accept("Nothing to redo"); return false; }
        logic$restore(result.document());
        status.accept("Redo: " + result.label());
        return true;
    }

    @Unique
    private void logic$restore(CircuitDocument snapshot) {
        CircuitDocument restored = EditorDocumentSnapshot.copy(snapshot);
        document = restored;
        if (CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) runtimeRootDocument = restored;

        Set<Integer> validInputs = new LinkedHashSet<>();
        for (EditorNode node : restored.nodes) if (node.kind == NodeKind.INPUT) validInputs.add(node.id);
        inputStates.keySet().retainAll(validInputs);
        for (EditorNode node : restored.nodes) if (node.kind == NodeKind.INPUT) inputStates.putIfAbsent(node.id, node.inputDefaultValue);

        logic$selectedPins.clear();
        logic$errorPins.clear();
        logic$selectionMode = LogicSelectionMode.COMPONENT;
        clearSelection();
        recompile();
    }

    @Inject(method = "deleteSelectionConfirmed", at = @At("HEAD"))
    private void logic$beforeDelete(CallbackInfo ci) { logic$history.checkpoint("Delete", document); }
    @Inject(method = "deleteSelectionConfirmed", at = @At("RETURN"))
    private void logic$afterDelete(CallbackInfo ci) { logic$history.commit(document); }

    @Inject(method = "changeSelectedWidth", at = @At("HEAD"))
    private void logic$beforeWidth(int direction, CallbackInfo ci) { logic$history.checkpoint("Change width", document); }
    @Inject(method = "changeSelectedWidth", at = @At("RETURN"))
    private void logic$afterWidth(int direction, CallbackInfo ci) { logic$history.commit(document); }

    @Inject(method = "addRoutePointToSelection", at = @At("HEAD"))
    private void logic$beforeRoutePoint(CallbackInfoReturnable<Boolean> cir) { logic$history.checkpoint("Wire route", document); }
    @Inject(method = "addRoutePointToSelection", at = @At("RETURN"))
    private void logic$afterRoutePoint(CallbackInfoReturnable<Boolean> cir) { logic$history.commit(document); }

    @Inject(method = "renameSelectedIo", at = @At("HEAD"))
    private void logic$beforeRenameIo(String name, CallbackInfoReturnable<Boolean> cir) { logic$history.checkpoint("Rename port", document); }
    @Inject(method = "renameSelectedIo", at = @At("RETURN"))
    private void logic$afterRenameIo(String name, CallbackInfoReturnable<Boolean> cir) { logic$history.commit(document); }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$resetHistoryForDocument(CircuitDocument replacement, String rootChipName, CallbackInfo ci) {
        logic$history.clear(); logic$selectedPins.clear(); logic$errorPins.clear(); logic$selectionMode = LogicSelectionMode.COMPONENT;
    }

    @Inject(method = "openNestedChip", at = @At("RETURN"))
    private void logic$resetHistoryForNested(EditorNode node, CallbackInfo ci) {
        logic$history.clear(); logic$selectedPins.clear(); logic$errorPins.clear(); logic$selectionMode = LogicSelectionMode.COMPONENT;
    }

    @Inject(method = "navigateBack", at = @At("RETURN"))
    private void logic$resetHistoryAfterBack(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            logic$history.clear(); logic$selectedPins.clear(); logic$errorPins.clear(); logic$selectionMode = LogicSelectionMode.COMPONENT;
        }
    }

    @Unique private enum LogicSelectionMode { COMPONENT, PIN }
    @Unique private record LogicPinKey(int nodeId, int port, boolean input) {}
    @Unique private record LogicPinPoint(double x, double y) {}
    @Unique private record LogicPinHit(LogicPinKey key, PortSpec spec, LogicPinPoint point) {}
}
