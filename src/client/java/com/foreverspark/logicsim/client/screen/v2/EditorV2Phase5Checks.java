package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;

import java.util.Arrays;
import java.util.List;

/** Dependency-light regression checks for persistent world-connected DEVICE nodes. */
public final class EditorV2Phase5Checks {
    private EditorV2Phase5Checks() {}

    public static void run() {
        catalogChecks();
        compilerUnknownChecks();
        discoveryMergeChecks();
        snapshotChecks();
        legacyDisplayMigrationChecks();
    }

    private static void catalogChecks() {
        List<String> names = Arrays.stream(ExternalDeviceType.values()).map(Enum::name).toList();
        check(names.equals(List.of("DISPLAY", "UIB", "INTERNET", "STORAGE")),
                "physical DEVICE catalog contains peripherals only");
        check(names.stream().noneMatch(name -> name.contains("CPU") || name.contains("GPU") || name.contains("RAM") || name.contains("ROM")),
                "CPU/GPU/RAM/ROM are not fake physical devices");
        EditorNode internet = new EditorNode(1, NodeKind.EXTERNAL_DEVICE, 0, 0);
        internet.externalDeviceType = ExternalDeviceType.INTERNET;
        check(NodePorts.inputs(internet, name -> null).size() == 2, "INTERNET exposes TX and CONTROL inputs");
        check(NodePorts.outputs(internet, name -> null).size() == 3, "INTERNET exposes RX STATUS IRQ outputs");
    }

    private static void compilerUnknownChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode source = document.addNode(NodeKind.INPUT, 0, 0);
        source.width = 16;
        EditorNode device = document.addNode(NodeKind.EXTERNAL_DEVICE, 80, 0);
        device.configureExternalDevice(ExternalDeviceType.UIB, "u-1", ExternalDeviceState.CONNECTED, "test", 1, 2, 3);
        document.connect(source.id, 0, device.id, 0);

        var compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(source.id, 0xA55AL);
        check(compiled.inputUnsigned(device.id, 0) == 0xA55AL, "board can drive a physical DEVICE input without magical logic");
        LogicValue[] keyboard = compiled.outputValues(device.id, 0);
        check(keyboard.length == 64, "UIB keyboard output width is 64 bits");
        check(Arrays.stream(keyboard).allMatch(value -> value == LogicValue.UNKNOWN),
                "host-driven DEVICE outputs are UNKNOWN instead of fabricated LOW values");
    }

    private static void discoveryMergeChecks() {
        CircuitDocument board = new CircuitDocument();
        ExternalDeviceDescriptor uib = new ExternalDeviceDescriptor("uib-123", ExternalDeviceType.UIB, "world", 10, 64, 20);
        ExternalDeviceSync.Result first = ExternalDeviceSync.reconcile(board, List.of(uib));
        check(first.connected() == 1 && first.created() == 1 && first.unknown() == 0, "first discovery creates one connected DEVICE node");
        EditorNode node = board.externalDeviceNodes().getFirst();
        int originalNodeId = node.id;
        node.x = 222;
        node.y = 96;

        ExternalDeviceSync.Result disconnected = ExternalDeviceSync.reconcile(board, List.of());
        check(disconnected.unknown() == 1, "missing world endpoint persists as UNKNOWN");
        check(board.externalDeviceNodes().getFirst().id == originalNodeId, "disconnect does not delete schematic DEVICE node");
        check(board.externalDeviceNodes().getFirst().x == 222, "disconnect preserves schematic placement");

        ExternalDeviceSync.Result reconnected = ExternalDeviceSync.reconcile(board, List.of(
                new ExternalDeviceDescriptor("uib-123", ExternalDeviceType.UIB, "world", 30, 70, 40)));
        EditorNode same = board.externalDeviceNodes().getFirst();
        check(reconnected.created() == 0 && same.id == originalNodeId, "stable device id reconnects the same schematic node");
        check(same.externalDeviceState == ExternalDeviceState.CONNECTED && same.externalDeviceX == 30,
                "reconnect refreshes world state without replacing the node");
    }

    private static void snapshotChecks() {
        CircuitDocument board = new CircuitDocument();
        EditorNode device = board.addNode(NodeKind.EXTERNAL_DEVICE, 12, 18);
        device.configureExternalDevice(ExternalDeviceType.STORAGE, "disk-a", ExternalDeviceState.UNKNOWN, "dim", 4, 5, 6);
        CircuitDocument copy = EditorDocumentSnapshot.copy(board);
        check(EditorDocumentSnapshot.same(board, copy), "undo snapshot preserves DEVICE identity/state/world coordinates");
        copy.externalDeviceNodes().getFirst().externalDeviceId = "changed";
        check(board.externalDeviceNodes().getFirst().externalDeviceId.equals("disk-a"), "DEVICE snapshot metadata is deep copied");
    }

    private static void legacyDisplayMigrationChecks() {
        CircuitDocument legacy = new CircuitDocument();
        EditorNode old = legacy.addCustomChip("DISPLAY", 0, 0);
        legacy.normalize();
        check(old.kind == NodeKind.EXTERNAL_DEVICE && old.externalDeviceType == ExternalDeviceType.DISPLAY,
                "legacy fake DISPLAY custom-chip node migrates to physical DEVICE placeholder");
        check(old.externalDeviceState == ExternalDeviceState.UNKNOWN, "legacy display starts UNKNOWN until world discovery binds it");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
