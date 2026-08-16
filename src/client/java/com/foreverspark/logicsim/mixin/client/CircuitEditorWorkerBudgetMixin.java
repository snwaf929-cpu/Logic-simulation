package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.block.CircuitWorkerPolicy;
import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.CircuitWorkerConfigScreen;
import com.foreverspark.logicsim.client.screen.v2.EditorWorkspaceAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** P opens the physical BOARD simulation-worker budget without changing circuit logic. */
@Mixin(value = CircuitEditorScreen.class, priority = 2350)
public abstract class CircuitEditorWorkerBudgetMixin {
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$workerBudgetShortcut(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != GLFW.GLFW_KEY_P || event.modifiers() != 0) return;

        Object self = this;
        if (!(self instanceof EditorWorkspaceAccess workspace) || !workspace.logic$isBoardWorkspace()) {
            setStatus("SIM WORKERS belong to a physical BOARD/Circuit Block, not a reusable CHIP definition");
            cir.setReturnValue(true);
            return;
        }

        CircuitDocument root = workspace.logic$boardRootDocument();
        if (root == null) return;
        CircuitEditorScreen parent = (CircuitEditorScreen)(Object)this;
        Minecraft.getInstance().gui.setScreen(new CircuitWorkerConfigScreen(parent, root.simulationWorkers, requested -> {
            root.simulationWorkers = CircuitWorkerPolicy.normalizePersisted(requested);
            root.normalize();

            // Save the worker budget immediately. It is runtime scheduling metadata, so changing it does not recompile
            // or restart the computer; CircuitSimulationWorker notices the new canonical board String next server tick.
            BlockPos pos = ClientEditorBridge.activeCircuitPos();
            if (pos != null) ClientBoardNetworking.save(pos, root);

            int max = CircuitWorkerPolicy.systemMaximum(Runtime.getRuntime().availableProcessors());
            setStatus("SIM WORKERS = " + CircuitWorkerPolicy.label(root.simulationWorkers, max)
                    + " — shared/dynamic; unloaded or disabled computers do not reserve workers");
        }));
        cir.setReturnValue(true);
    }
}
