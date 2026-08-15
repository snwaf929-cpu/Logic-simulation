package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles transient world discovery with persistent schematic DEVICE nodes.
 *
 * <p>V2.1A deliberately never creates canvas nodes from discovery. Physical devices first appear in the
 * DEVICES library; a user explicitly places a reference on a BOARD. Once placed, that reference survives
 * unplugging and reconnects by stable device id without moving or rewiring it.</p>
 */
public final class ExternalDeviceSync {
    private ExternalDeviceSync() {}

    public static Result reconcile(CircuitDocument document, List<ExternalDeviceDescriptor> discovered) {
        if (document == null) throw new IllegalArgumentException("Board document is required");
        document.normalize();
        List<ExternalDeviceDescriptor> safe = discovered == null ? List.of() : List.copyOf(discovered);

        Map<String, ExternalDeviceDescriptor> byId = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        for (ExternalDeviceDescriptor descriptor : safe) {
            if (descriptor == null || descriptor.deviceId() == null || descriptor.deviceId().isBlank()) continue;
            String id = descriptor.deviceId().trim();
            if (byId.putIfAbsent(id, descriptor) != null) duplicateIds.add(id);
        }

        int connected = 0;
        int disconnected = 0;
        int unknown = 0;
        boolean changed = false;

        for (EditorNode node : document.externalDeviceNodes()) {
            String id = node.externalDeviceId == null ? "" : node.externalDeviceId.trim();
            if (id.isEmpty()) {
                if (node.externalDeviceState != ExternalDeviceState.UNKNOWN) changed = true;
                node.externalDeviceState = ExternalDeviceState.UNKNOWN;
                unknown++;
                continue;
            }

            ExternalDeviceDescriptor descriptor = duplicateIds.contains(id) ? null : byId.get(id);
            if (descriptor == null) {
                if (node.externalDeviceState != ExternalDeviceState.DISCONNECTED) changed = true;
                node.externalDeviceState = ExternalDeviceState.DISCONNECTED;
                disconnected++;
                continue;
            }

            if (node.externalDeviceState != ExternalDeviceState.CONNECTED
                    || node.externalDeviceType != descriptor.type()
                    || !same(node.externalDeviceWorld, descriptor.world())
                    || node.externalDeviceX != descriptor.x()
                    || node.externalDeviceY != descriptor.y()
                    || node.externalDeviceZ != descriptor.z()) {
                changed = true;
            }
            node.configureExternalDevice(descriptor.type(), id, ExternalDeviceState.CONNECTED,
                    descriptor.world(), descriptor.x(), descriptor.y(), descriptor.z());
            connected++;
        }

        return new Result(connected, disconnected, unknown, changed);
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    public record Result(int connected, int disconnected, int unknown, boolean changed) {}
}
