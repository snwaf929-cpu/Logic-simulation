package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.ClientBoardNetworking;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.v2.EditorWorkspaceAccess;
import com.foreverspark.logicsim.client.screen.v2.ExternalDeviceLibraryAccess;
import com.foreverspark.logicsim.client.screen.v2.ExternalDeviceSync;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.platform.ClientEditorBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Live physical-device discovery for the world BOARD. No focus change or editor reopen is required. */
@Mixin(value = CircuitEditorScreen.class, priority = 1200)
public abstract class CircuitEditorPhase5DevicesMixin {
    @Unique private static final long LOGIC_DEVICE_REFRESH_NANOS = 250_000_000L;

    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private ComponentLibraryWidget componentLibrary;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Unique private long logic$lastDeviceRequestNanos;
    @Unique private long logic$appliedDeviceRevision = Long.MIN_VALUE;
    @Unique private BlockPos logic$deviceBoardPos;

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$initializePhysicalDevices(CallbackInfo ci) {
        logic$lastDeviceRequestNanos = 0L;
        logic$appliedDeviceRevision = Long.MIN_VALUE;
        logic$deviceBoardPos = ClientEditorBridge.activeCircuitPos();
        logic$updateDeviceLibraryMode();
        logic$applyLatestDeviceSnapshot(true);
        if (logic$deviceBoardPos != null) ClientBoardNetworking.requestDevices(logic$deviceBoardPos);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void logic$pollPhysicalDevices(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci
    ) {
        if (canvas == null || componentLibrary == null) return;
        BlockPos active = ClientEditorBridge.activeCircuitPos();
        if (active == null) {
            logic$deviceBoardPos = null;
            ((ExternalDeviceLibraryAccess) (Object) componentLibrary).logic$setDeviceLibraryEnabled(false);
            ((ExternalDeviceLibraryAccess) (Object) componentLibrary).logic$setAvailableDevices(List.of());
            return;
        }
        if (!active.equals(logic$deviceBoardPos)) {
            logic$deviceBoardPos = active.immutable();
            logic$appliedDeviceRevision = Long.MIN_VALUE;
            logic$lastDeviceRequestNanos = 0L;
        }

        logic$updateDeviceLibraryMode();
        logic$applyLatestDeviceSnapshot(false);

        long now = System.nanoTime();
        if (now - logic$lastDeviceRequestNanos >= LOGIC_DEVICE_REFRESH_NANOS) {
            logic$lastDeviceRequestNanos = now;
            ClientBoardNetworking.requestDevices(logic$deviceBoardPos);
        }
    }

    @Unique
    private void logic$updateDeviceLibraryMode() {
        if (componentLibrary == null) return;
        boolean boardWorkspace = ((EditorWorkspaceAccess) (Object) this).logic$isBoardWorkspace();
        ((ExternalDeviceLibraryAccess) (Object) componentLibrary)
                .logic$setDeviceLibraryEnabled(boardWorkspace && logic$deviceBoardPos != null);
    }

    @Unique
    private void logic$applyLatestDeviceSnapshot(boolean announce) {
        if (logic$deviceBoardPos == null || canvas == null || componentLibrary == null) return;
        long revision = ClientBoardNetworking.deviceSnapshotRevision(logic$deviceBoardPos);
        if (revision < 0L || revision == logic$appliedDeviceRevision) return;
        logic$appliedDeviceRevision = revision;

        List<ExternalDeviceDescriptor> devices = ClientBoardNetworking.latestDevices(logic$deviceBoardPos);
        ((ExternalDeviceLibraryAccess) (Object) componentLibrary).logic$setAvailableDevices(devices);

        CircuitDocument board = ((EditorWorkspaceAccess) (Object) this).logic$boardRootDocument();
        if (board == null) board = ((CanvasAccess) (Object) canvas).logic$getRuntimeRootDocument();
        if (board == null) board = canvas.document();

        ExternalDeviceSync.Result result = ExternalDeviceSync.reconcile(board, devices);
        if (result.changed()) ((CanvasAccess) (Object) canvas).logic$recompile();

        if (announce) {
            setStatus("DEVICES: " + result.connected() + " placed+connected, " + devices.size() + " available"
                    + (result.disconnected() > 0 ? ", " + result.disconnected() + " disconnected references retained" : ""));
        }
    }
}
