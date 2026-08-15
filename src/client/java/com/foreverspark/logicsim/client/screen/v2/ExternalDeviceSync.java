package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconciles transient world discovery with persistent schematic DEVICE nodes. */
public final class ExternalDeviceSync {
    private ExternalDeviceSync() {}

    public static Result reconcile(CircuitDocument document, List<ExternalDeviceDescriptor> discovered) {
        if (document == null) throw new IllegalArgumentException("Board document is required");
        document.normalize();
        List<ExternalDeviceDescriptor> safe = discovered == null ? List.of() : List.copyOf(discovered);

        Map<String, EditorNode> existing = new HashMap<>();
        for (EditorNode node : document.externalDeviceNodes()) {
            node.externalDeviceState = ExternalDeviceState.UNKNOWN;
            if (node.externalDeviceId != null && !node.externalDeviceId.isBlank()) existing.put(node.externalDeviceId, node);
        }

        double baseX = document.nodes.stream().filter(node -> !node.isExternalDevice())
                .mapToDouble(node -> node.x).max().orElse(0.0) + 150.0;
        double baseY = document.nodes.stream().filter(EditorNode::isExternalDevice)
                .mapToDouble(node -> node.y).max().orElse(-72.0) + 72.0;
        int created = 0;
        int connected = 0;
        Set<String> seen = new HashSet<>();

        for (ExternalDeviceDescriptor descriptor : safe) {
            if (descriptor == null || descriptor.deviceId() == null || descriptor.deviceId().isBlank()) continue;
            if (!seen.add(descriptor.deviceId())) continue;
            EditorNode node = existing.get(descriptor.deviceId());
            if (node == null) {
                node = document.addNode(NodeKind.EXTERNAL_DEVICE, EditorGrid.snap(baseX), EditorGrid.snap(baseY + created * 72.0));
                created++;
            }
            node.configureExternalDevice(descriptor.type(), descriptor.deviceId(), ExternalDeviceState.CONNECTED,
                    descriptor.world(), descriptor.x(), descriptor.y(), descriptor.z());
            connected++;
        }
        document.normalize();
        int unknown = (int) document.externalDeviceNodes().stream()
                .filter(node -> node.externalDeviceState == ExternalDeviceState.UNKNOWN).count();
        return new Result(connected, created, unknown);
    }

    public record Result(int connected, int created, int unknown) {}
}
