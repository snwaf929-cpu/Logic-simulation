package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.ChipPortOrderAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorHistoryAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Stable, explicitly reorderable public INPUT/OUTPUT interface for reusable CHIP definitions. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2380)
public abstract class CircuitCanvasChipPortOrderMixin implements ChipPortOrderAccess {
    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private Consumer<String> status;

    @Shadow private void recompile() { throw new AssertionError(); }
    @Shadow private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow private double nodeWidth(EditorNode node) { throw new AssertionError(); }

    @Override
    public boolean logic$moveSelectedChipPort(int direction) {
        if (document == null || selectedNodeIds.size() != 1 || selectedNodeId == null) return false;
        EditorNode selected;
        try {
            selected = document.node(selectedNodeId);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (selected.kind != NodeKind.INPUT && selected.kind != NodeKind.OUTPUT) {
            status.accept("CHIP PORT ORDER: select exactly one INPUT or OUTPUT terminal");
            return false;
        }

        document.normalize();
        List<EditorNode> ordered = selected.kind == NodeKind.INPUT ? document.inputNodes() : document.outputNodes();
        int current = logic$indexOf(ordered, selected.id);
        if (current < 0) return false;
        int target = Math.max(0, Math.min(ordered.size() - 1, current + Integer.signum(direction)));
        if (target == current) {
            status.accept("CHIP PORT ORDER: " + selected.displayName() + " is already "
                    + (current == 0 ? "first" : "last") + " in the " + selected.kind + " interface");
            return true;
        }

        logic$historyCheckpoint("Reorder CHIP port");
        EditorNode other = ordered.get(target);
        int swap = selected.chipPortOrder;
        selected.chipPortOrder = other.chipPortOrder;
        other.chipPortOrder = swap;
        document.normalize();
        recompile();
        logic$historyCommit();

        List<EditorNode> after = selected.kind == NodeKind.INPUT ? document.inputNodes() : document.outputNodes();
        int newIndex = logic$indexOf(after, selected.id);
        status.accept("CHIP PORT ORDER: " + selected.displayName() + " = " + (newIndex + 1) + "/" + after.size()
                + " on reusable CHIP " + selected.kind + " side");
        return true;
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$drawSelectedPortOrder(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (document == null || selectedNodeIds.size() != 1 || selectedNodeId == null) return;
        EditorNode node;
        try {
            node = document.node(selectedNodeId);
        } catch (RuntimeException ignored) {
            return;
        }
        if (node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT) return;

        List<EditorNode> ordered = node.kind == NodeKind.INPUT ? document.inputNodes() : document.outputNodes();
        int index = logic$indexOf(ordered, node.id);
        if (index < 0) return;
        String text = "CHIP PORT " + (index + 1) + "/" + ordered.size() + "   Ctrl+Shift+Up/Down";
        int textWidth = Minecraft.getInstance().font.width(text);
        int x = screenX(node.x + nodeWidth(node) * 0.5) - textWidth / 2 - 4;
        int y = screenY(node.y) - 20;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        x = Math.max(self.getX() + 4, Math.min(x, self.getX() + self.getWidth() - textWidth - 10));
        y = Math.max(self.getY() + 4, y);
        graphics.fill(x, y, x + textWidth + 8, y + 15, 0xE0151B22);
        graphics.outline(x, y, textWidth + 8, 15, 0xFF63A9D8);
        graphics.text(Minecraft.getInstance().font, text, x + 4, y + 4, 0xFFE6EDF4, false);
    }

    @Unique private static int logic$indexOf(List<EditorNode> nodes, int id) {
        for (int index = 0; index < nodes.size(); index++) if (nodes.get(index).id == id) return index;
        return -1;
    }

    @Unique private void logic$historyCheckpoint(String label) {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$checkpoint(label);
    }

    @Unique private void logic$historyCommit() {
        Object self = this;
        if (self instanceof EditorHistoryAccess history) history.logic$commitHistory();
    }
}
