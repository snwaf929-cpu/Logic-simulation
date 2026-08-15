package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import com.foreverspark.logicsim.client.screen.Phase4SocketConfigScreen;
import com.foreverspark.logicsim.client.screen.v2.BoardTemplateCanvasAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorDocumentSnapshot;
import com.foreverspark.logicsim.client.screen.v2.EditorGrid;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.BoardTemplateEngine;
import com.foreverspark.logicsim.editor.model.BoardTemplateReplacementPreview;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** BOARD-template insertion/replacement and authored socket configuration. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2600)
public abstract class CircuitCanvasPhase4TemplateMixin implements BoardTemplateCanvasAccess {
    @Shadow private CircuitDocument document;
    @Shadow private CircuitDocument runtimeRootDocument;
    @Shadow private String runtimeScopePath;
    @Shadow @Final private ClientChipLibrary chips;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private Consumer<String> status;

    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void setSelectedNodes(Iterable<Integer> nodeIds) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private double worldX(double screenX) { throw new AssertionError(); }
    @Shadow private double worldY(double screenY) { throw new AssertionError(); }

    @Unique private boolean logic$socketPlacementArmed;
    @Unique private PortDirection logic$pendingSocketDirection = PortDirection.INPUT;

    @Override
    public void logic$beginSocketPlacement(PortDirection direction) {
        if (!CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) {
            status.accept("BOARD SOCKET: go back to the root BOARD before placing a socket");
            return;
        }
        logic$pendingSocketDirection = direction == null ? PortDirection.INPUT : direction;
        logic$socketPlacementArmed = true;
        ((CircuitCanvasWidget)(Object)this).setPlacement(NodeKind.BUS);
        status.accept("Place " + logic$pendingSocketDirection + " BOARD SOCKET — click the board; its interface editor opens automatically");
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$socketDoubleClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 || !doubleClick || logic$socketPlacementArmed) return;
        EditorNode node = nodeAt(event.x(), event.y());
        if (node == null || !node.isBoardSocket()) return;
        if (node.templateInstanceId > 0) {
            status.accept("This socket belongs to BOARD template instance " + node.templateName + " — edit/save the template source instead");
            ci.cancel();
            return;
        }
        logic$openSocketConfig(node, EditorScreenContext.current());
        ci.cancel();
    }

    @Inject(method = "onClick", at = @At("RETURN"))
    private void logic$finishSocketPlacement(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (!logic$socketPlacementArmed || event.button() != 0) return;
        if (selectedNodeId == null) return;
        EditorNode node;
        try {
            node = document.node(selectedNodeId);
        } catch (RuntimeException ignored) {
            return;
        }
        if (node.kind != NodeKind.BUS || node.boardSocket) return;
        logic$socketPlacementArmed = false;
        int order = logic$nextSocketOrder(node.id);
        node.configureBoardSocket("SOCKET" + node.id, logic$pendingSocketDirection, order);
        node.width = 1;
        recompile();
        logic$openSocketConfig(node, EditorScreenContext.current());
    }

    @Inject(method = "cancelTransientMode", at = @At("RETURN"))
    private void logic$clearSocketPlacementOnCancel(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) logic$socketPlacementArmed = false;
    }

    @Inject(method = "setDocument(Lcom/foreverspark/logicsim/editor/model/CircuitDocument;Ljava/lang/String;)V", at = @At("RETURN"))
    private void logic$clearPhase4Transient(CircuitDocument replacement, String name, CallbackInfo ci) {
        logic$socketPlacementArmed = false;
    }

    @Override
    public boolean logic$configureSelectedSocket(Screen parent) {
        if (selectedNodeIds.size() != 1 || selectedNodeId == null) return false;
        EditorNode node;
        try {
            node = document.node(selectedNodeId);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (!node.isBoardSocket()) return false;
        if (node.templateInstanceId > 0) {
            status.accept("Template-instance sockets are controlled by their BOARD template source");
            return true;
        }
        logic$openSocketConfig(node, parent);
        return true;
    }

    @Override
    public int logic$selectedTemplateInstanceId() {
        if (selectedNodeIds.isEmpty()) return 0;
        int result = 0;
        for (Integer nodeId : selectedNodeIds) {
            if (nodeId == null) return 0;
            EditorNode node;
            try {
                node = document.node(nodeId);
            } catch (RuntimeException ignored) {
                return 0;
            }
            if (node.templateInstanceId <= 0) return 0;
            if (result == 0) result = node.templateInstanceId;
            else if (result != node.templateInstanceId) return 0;
        }
        return result;
    }

    @Override
    public boolean logic$insertBoardTemplate(BoardTemplateDefinition template) {
        if (!CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) {
            status.accept("BOARD TEMPLATE: go back to the root BOARD before inserting a template");
            return false;
        }
        if (template == null) return false;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        double originX = EditorGrid.snap(worldX(self.getX() + self.getWidth() * 0.5));
        double originY = EditorGrid.snap(worldY(self.getY() + self.getHeight() * 0.5));
        CircuitDocument candidate = EditorDocumentSnapshot.copy(document);
        BoardTemplateEngine.InsertResult inserted;
        try {
            inserted = BoardTemplateEngine.insert(candidate, template, originX, originY);
            CircuitCompiler.compile(candidate, chips);
        } catch (RuntimeException exception) {
            status.accept("BOARD TEMPLATE INSERT REJECTED: " + logic$message(exception));
            return false;
        }

        logic$templateHistoryCheckpoint("Insert board template");
        logic$adoptDocument(candidate);
        setSelectedNodes(inserted.nodeIds());
        recompile();
        logic$templateHistoryCommit();
        status.accept("Inserted BOARD template " + template.name + " as instance " + inserted.instanceId()
                + " — " + template.sockets().size() + " socket" + (template.sockets().size() == 1 ? "" : "s"));
        return true;
    }

    @Override
    public BoardTemplateReplacementPreview logic$previewTemplateReplacement(BoardTemplateDefinition template) {
        int instanceId = logic$selectedTemplateInstanceId();
        if (instanceId <= 0 || template == null) return null;
        return BoardTemplateEngine.previewReplacement(document, instanceId, template);
    }

    @Override
    public boolean logic$replaceSelectedTemplate(BoardTemplateDefinition template) {
        int instanceId = logic$selectedTemplateInstanceId();
        if (instanceId <= 0) {
            status.accept("BOARD TEMPLATE REPLACE: select one node or the full group from a template instance first");
            return false;
        }
        CircuitDocument candidate = EditorDocumentSnapshot.copy(document);
        BoardTemplateEngine.ReplaceResult replaced;
        try {
            replaced = BoardTemplateEngine.replace(candidate, instanceId, template);
            CircuitCompiler.compile(candidate, chips);
        } catch (RuntimeException exception) {
            status.accept("BOARD TEMPLATE REPLACE REJECTED: " + logic$message(exception));
            return false;
        }

        logic$templateHistoryCheckpoint("Replace board template");
        logic$adoptDocument(candidate);
        setSelectedNodes(replaced.inserted().nodeIds());
        recompile();
        logic$templateHistoryCommit();
        status.accept("Replaced BOARD template instance " + instanceId + " with " + template.name
                + " — preserved " + replaced.preview().externalConnections() + " external connection"
                + (replaced.preview().externalConnections() == 1 ? "" : "s"));
        return true;
    }

    @Unique
    private void logic$openSocketConfig(EditorNode node, Screen parent) {
        Minecraft.getInstance().gui.setScreen(new Phase4SocketConfigScreen(
                parent,
                node.label,
                node.interfaceId,
                node.socketDirection,
                node.width,
                node.interfaceOrder,
                (name, interfaceId, direction, width, order) -> logic$applySocketConfig(node.id, name, interfaceId, direction, width, order)
        ));
    }

    @Unique
    private void logic$applySocketConfig(int nodeId, String name, String interfaceId, PortDirection direction, int width, int order) {
        EditorNode node = document.node(nodeId);
        logic$validateSocketIdentityAndOrder(nodeId, interfaceId, order);
        logic$templateHistoryCheckpoint("Configure board socket");
        boolean widthChanged = node.width != width;
        node.configureBoardSocket(name, direction, order);
        node.interfaceId = interfaceId.trim();
        node.width = width;
        int removed = 0;
        if (widthChanged) {
            int before = document.wires.size();
            document.wires.removeIf(wire -> wire.sourceNodeId() == node.id || wire.targetNodeId() == node.id);
            removed = before - document.wires.size();
        }
        recompile();
        logic$templateHistoryCommit();
        status.accept("SOCKET " + node.label + "  " + node.socketDirection + "  [" + node.width + "]  order " + node.interfaceOrder
                + (removed > 0 ? " — removed " + removed + " width-dependent wire" + (removed == 1 ? "" : "s") : ""));
    }

    @Unique
    private void logic$validateSocketIdentityAndOrder(int nodeId, String interfaceId, int order) {
        String identity = interfaceId == null ? "" : interfaceId.trim();
        if (identity.isEmpty()) throw new IllegalArgumentException("Stable interface identity is required");
        for (EditorNode candidate : document.nodes) {
            if (candidate.id == nodeId || !candidate.isBoardSocket() || candidate.templateInstanceId > 0) continue;
            if (candidate.interfaceId.equalsIgnoreCase(identity)) throw new IllegalArgumentException("Interface ID already used by socket " + candidate.label);
            if (candidate.interfaceOrder == order) throw new IllegalArgumentException("Socket order " + order + " is already used by " + candidate.label);
        }
    }

    @Unique
    private int logic$nextSocketOrder(int ignoreNodeId) {
        int max = -1;
        for (EditorNode node : document.nodes) {
            if (node.id == ignoreNodeId || !node.isBoardSocket() || node.templateInstanceId > 0) continue;
            max = Math.max(max, node.interfaceOrder);
        }
        return max + 1;
    }

    @Unique
    private void logic$adoptDocument(CircuitDocument replacement) {
        document = replacement;
        if (CompiledCircuit.ROOT_SCOPE.equals(runtimeScopePath)) runtimeRootDocument = replacement;
    }

    @Unique
    private void logic$templateHistoryCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique
    private void logic$templateHistoryCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }

    @Unique
    private static String logic$message(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
