package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.v2.ExternalDevicePlacementAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * V2.1A device placement is opt-in: discovery fills the DEVICES library and only a user click creates a node.
 */
@Mixin(CircuitCanvasWidget.class)
public abstract class CircuitCanvasExternalDevicePlacementMixin implements ExternalDevicePlacementAccess {
    @Shadow public abstract CircuitDocument document();
    @Shadow public abstract void setPlacement(NodeKind kind);
    @Shadow public abstract void cancelPlacement();

    @Unique private ExternalDeviceDescriptor logic$pendingDevice;
    @Unique private ExternalDeviceDescriptor logic$clickDevice;
    @Unique private Set<Integer> logic$deviceIdsBeforeClick = Set.of();

    @Override
    public boolean logic$beginExternalDevicePlacement(ExternalDeviceDescriptor descriptor) {
        if (descriptor == null || descriptor.deviceId() == null || descriptor.deviceId().isBlank()) return false;
        CircuitDocument document = document();
        if (document == null) return false;
        for (EditorNode node : document.externalDeviceNodes()) {
            if (descriptor.deviceId().equals(node.externalDeviceId)) return false;
        }
        logic$pendingDevice = descriptor;
        setPlacement(NodeKind.EXTERNAL_DEVICE);
        return true;
    }

    @Override
    public boolean logic$externalDevicePlacementPending() {
        return logic$pendingDevice != null;
    }

    @Inject(method = "setPlacement", at = @At("HEAD"))
    private void logic$clearDevicePlacementForOtherTools(NodeKind kind, CallbackInfo ci) {
        if (kind != NodeKind.EXTERNAL_DEVICE) logic$pendingDevice = null;
    }

    @Inject(method = "cancelPlacement", at = @At("RETURN"))
    private void logic$clearDevicePlacement(CallbackInfo ci) {
        logic$pendingDevice = null;
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$prepareDevicePlacement(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (logic$pendingDevice == null) return;
        if (event.button() == 1) {
            cancelPlacement();
            ci.cancel();
            return;
        }
        if (event.button() != 0) return;
        logic$clickDevice = logic$pendingDevice;
        HashSet<Integer> ids = new HashSet<>();
        for (EditorNode node : document().externalDeviceNodes()) ids.add(node.id);
        logic$deviceIdsBeforeClick = ids;
    }

    @Inject(method = "onClick", at = @At("RETURN"))
    private void logic$finishDevicePlacement(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        ExternalDeviceDescriptor descriptor = logic$clickDevice;
        logic$clickDevice = null;
        if (descriptor == null || event.button() != 0) return;

        EditorNode created = null;
        for (EditorNode node : document().externalDeviceNodes()) {
            if (!logic$deviceIdsBeforeClick.contains(node.id)) {
                created = node;
                break;
            }
        }
        logic$deviceIdsBeforeClick = Set.of();
        if (created == null) return;

        created.configureExternalDevice(
                descriptor.type(), descriptor.deviceId(), ExternalDeviceState.CONNECTED,
                descriptor.world(), descriptor.x(), descriptor.y(), descriptor.z());
        ((CanvasAccess) (Object) this).logic$recompile();
        logic$pendingDevice = null;
    }
}
