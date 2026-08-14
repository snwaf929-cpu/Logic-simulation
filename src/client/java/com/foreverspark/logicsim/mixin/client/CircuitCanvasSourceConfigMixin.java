package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorClockRuntime;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import com.foreverspark.logicsim.client.screen.SourceConfigScreen;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;
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
import java.util.function.Consumer;

/** CLOCK/RANDOM configuration, including batch editing selected sources. */
@Mixin(value = CircuitCanvasWidget.class, priority = 900)
public abstract class CircuitCanvasSourceConfigMixin implements CanvasConfigAccess {
    @Shadow private Consumer<String> status;
    @Shadow private CircuitDocument document;
    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow private Integer selectedNodeId;
    @Shadow private WireConnection selectedWire;
    @Shadow private double panX;
    @Shadow private double panY;
    @Shadow private double zoom;
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$openSourceConfig(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0 || !doubleClick) return;
        EditorNode node = nodeAt(event.x(), event.y());
        if (node == null || node.kind != NodeKind.CONSTANT || (!node.clockSource && !node.randomSource)) return;

        logic$openNodes(List.of(node), EditorScreenContext.current());
        ci.cancel();
    }

    @Override
    public boolean logic$editSelectedSources(Screen parent) {
        if (selectedNodeIds.isEmpty()) return false;

        List<EditorNode> nodes = new ArrayList<>();
        for (Integer id : selectedNodeIds) {
            if (id == null) continue;
            EditorNode node;
            try {
                node = document.node(id);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (node.kind != NodeKind.CONSTANT || (!node.clockSource && !node.randomSource)) return false;
            nodes.add(node);
        }
        if (nodes.isEmpty()) return false;

        boolean clocks = nodes.getFirst().clockSource;
        boolean randoms = nodes.getFirst().randomSource;
        for (EditorNode node : nodes) {
            if (node.clockSource != clocks || node.randomSource != randoms) {
                status.accept("Select only CLOCKs or only RANDOMs before pressing E");
                return true;
            }
        }

        logic$openNodes(List.copyOf(nodes), parent);
        return true;
    }

    @Unique
    private void logic$openNodes(List<EditorNode> nodes, Screen parent) {
        if (nodes == null || nodes.isEmpty()) return;
        CircuitCanvasWidget canvas = (CircuitCanvasWidget)(Object)this;
        EditorNode first = nodes.getFirst();
        int count = nodes.size();

        if (first.clockSource) {
            Minecraft.getInstance().gui.setScreen(SourceConfigScreen.clock(parent, first.clockFrequencyHz, hz -> {
                for (EditorNode node : nodes) {
                    node.clockFrequencyHz = hz;
                    node.clockSource = true;
                    node.randomSource = false;
                    node.width = 1;
                    node.constantValue = 0L;
                }
                canvas.refreshLiveRuntime();
                EditorClockRuntime.attach(canvas);
                status.accept((count == 1 ? "CLOCK" : count + " CLOCKs") + " = "
                        + EditorNode.formatFrequency(hz) + " — selection and view preserved");
            }));
            return;
        }

        if (first.randomSource) {
            Minecraft.getInstance().gui.setScreen(SourceConfigScreen.random(parent, first.randomChancePercent, chance -> {
                for (EditorNode node : nodes) {
                    node.randomChancePercent = chance;
                    node.clockSource = false;
                    node.randomSource = true;
                    node.width = 1;
                    node.constantValue = 0L;
                }
                canvas.refreshLiveRuntime();
                EditorClockRuntime.attach(canvas);
                status.accept((count == 1 ? "RANDOM" : count + " RANDOMs") + " = " + chance
                        + "% chance of HIGH per TRIGGER 0 -> 1 edge — selection and view preserved");
            }));
        }
    }

    @Override
    public CanvasSessionState logic$captureSessionState() {
        return new CanvasSessionState(panX, panY, zoom, List.copyOf(selectedNodeIds));
    }

    @Override
    public void logic$restoreSessionState(CanvasSessionState state) {
        if (state == null) return;
        panX = state.panX();
        panY = state.panY();
        zoom = state.zoom();

        selectedNodeIds.clear();
        for (Integer id : state.selectedNodeIds()) {
            if (id == null) continue;
            try {
                document.node(id);
                selectedNodeIds.add(id);
            } catch (RuntimeException ignored) {
            }
        }
        selectedNodeId = selectedNodeIds.size() == 1 ? selectedNodeIds.getFirst() : null;
        selectedWire = null;
    }
}
