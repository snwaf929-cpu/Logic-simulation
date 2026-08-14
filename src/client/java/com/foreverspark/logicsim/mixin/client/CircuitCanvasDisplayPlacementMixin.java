package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Beginner-friendly placement for the physical screen interface. */
@Mixin(value = CircuitCanvasWidget.class, priority = 1350)
public abstract class CircuitCanvasDisplayPlacementMixin {
    private static final double GRID = 6.0;

    @Shadow private CircuitDocument document;
    @Shadow private NodeKind placementKind;
    @Shadow private String placementChipName;
    @Shadow private boolean wireEditMode;
    @Shadow private Consumer<String> status;

    @Shadow private double worldX(double screenX) { throw new AssertionError(); }
    @Shadow private double worldY(double screenY) { throw new AssertionError(); }
    @Shadow private void selectSingleNode(int nodeId) { throw new AssertionError(); }
    @Shadow private void recompile() { throw new AssertionError(); }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$placeScreenOutputBundle(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;
        if (placementKind != NodeKind.CUSTOM_CHIP || !BuiltinDevices.isDisplay(placementChipName)) return;

        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        if (event.x() < self.getX() || event.x() >= self.getX() + self.getWidth()
                || event.y() < self.getY() || event.y() >= self.getY() + self.getHeight()) return;

        double x = logic$snap(worldX(event.x()));
        double y = logic$snap(worldY(event.y()));

        EditorNode screen = document.addCustomChip(BuiltinDevices.DISPLAY, x, y);
        String outputName = logic$nextOutputName();

        double outputX = logic$snap(x + BuiltinDevices.displayVisual().width + 48.0);
        double outputY = logic$snap(y + (BuiltinDevices.displayVisual().minHeight - 42.0) * 0.5);
        EditorNode physicalOutput = document.addNode(NodeKind.OUTPUT, outputX, outputY);
        physicalOutput.width = 64;
        physicalOutput.label = outputName;

        document.connect(screen.id, 0, physicalOutput.id, 0);

        self.cancelPlacement();
        wireEditMode = false;
        selectSingleNode(screen.id);
        recompile();

        status.accept("SCREEN OUTPUT ready: DATA[64] is already wired to " + outputName
                + ". Feed X, Y, COLOR, DRAW/CLEAR; then Ctrl+S to program the Circuit Block. World connection = Bus Cable [64].");
        ci.cancel();
    }

    @Unique
    private String logic$nextOutputName() {
        Set<String> used = new HashSet<>();
        for (EditorNode node : document.outputNodes()) {
            if (node.label != null && !node.label.isBlank()) used.add(node.label.trim().toLowerCase(Locale.ROOT));
        }
        String base = "SCREEN_DATA";
        if (!used.contains(base.toLowerCase(Locale.ROOT))) return base;
        int suffix = 2;
        while (used.contains((base + suffix).toLowerCase(Locale.ROOT))) suffix++;
        return base + suffix;
    }

    @Unique
    private static double logic$snap(double value) {
        return Math.round(value / GRID) * GRID;
    }
}
