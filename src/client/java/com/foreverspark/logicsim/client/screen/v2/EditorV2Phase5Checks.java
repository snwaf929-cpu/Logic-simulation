package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;

import java.util.Arrays;
import java.util.List;

/** Dependency-light regression checks for the V2.1A explicit physical DEVICE workflow. */
public final class EditorV2Phase5Checks {
    private EditorV2Phase5Checks() {}

    public static void run() {
        catalogChecks();
        displayPortChecks();
        otherDevicePortChecks();
        compilerUnknownChecks();
        explicitPlacementSyncChecks();
        snapshotChecks();
        legacyDisplayMigrationChecks();
    }

    private static void catalogChecks() {
        List<String> names = Arrays.stream(ExternalDeviceType.values()).map(Enum::name).toList();
        check(names.equals(List.of("DISPLAY", "UIB", "INTERNET", "STORAGE")),
                "physical DEVICE catalog contains DISPLAY/UIB/INTERNET/STORAGE only");
        check(names.stream().noneMatch(name -> name.contains("CPU") || name.contains("GPU") || name.contains("RAM") || name.contains("ROM")),
                "CPU/GPU/RAM/ROM are not fake physical devices");
    }

    private static void displayPortChecks() {
        ExternalDeviceType display = ExternalDeviceType.DISPLAY;
        checkPorts(display.inputs(), List.of(
                new ExpectedPort("X", 16),
                new ExpectedPort("Y", 16),
                new ExpectedPort("COLOR", 16),
                new ExpectedPort("WRITE", 1),
                new ExpectedPort("RESET", 1)
        ), "DISPLAY");
        check(display.outputs().isEmpty(), "DISPLAY has no fabricated schematic outputs");
    }

    private static void otherDevicePortChecks() {
        checkPorts(ExternalDeviceType.UIB.inputs(), List.of(new ExpectedPort("CONTROL", 16)), "UIB inputs");
        checkPorts(ExternalDeviceType.UIB.outputs(), List.of(
                new ExpectedPort("KEYBOARD", 64), new ExpectedPort("MOUSE", 64), new ExpectedPort("IRQ", 1)
        ), "UIB outputs");

        checkPorts(ExternalDeviceType.INTERNET.inputs(), List.of(
                new ExpectedPort("TX", 64), new ExpectedPort("CONTROL", 16)
        ), "INTERNET inputs");
        checkPorts(ExternalDeviceType.INTERNET.outputs(), List.of(
                new ExpectedPort("RX", 64), new ExpectedPort("STATUS", 16), new ExpectedPort("IRQ", 1)
        ), "INTERNET outputs");

        checkPorts(ExternalDeviceType.STORAGE.inputs(), List.of(new ExpectedPort("COMMAND", 64)), "STORAGE inputs");
        checkPorts(ExternalDeviceType.STORAGE.outputs(), List.of(
                new ExpectedPort("RESPONSE", 64), new ExpectedPort("IRQ", 1)
        ), "STORAGE outputs");
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

    private static void explicitPlacementSyncChecks() {
        CircuitDocument board = new CircuitDocument();
        ExternalDeviceDescriptor uib = new ExternalDeviceDescriptor("uib-123", ExternalDeviceType.UIB, "world", 10, 64, 20);

        ExternalDeviceSync.Result discoveryOnly = ExternalDeviceSync.reconcile(board, List.of(uib));
        check(board.externalDeviceNodes().isEmpty(), "discovery never auto-places a DEVICE node");
        check(discoveryOnly.connected() == 0 && !discoveryOnly.changed(), "unplaced discovery does not mutate BOARD");

        EditorNode placed = board.addNode(NodeKind.EXTERNAL_DEVICE, 222, 96);
        placed.configureExternalDevice(ExternalDeviceType.UIB, "uib-123", ExternalDeviceState.CONNECTED, "world", 10, 64, 20);
        int originalNodeId = placed.id;

        ExternalDeviceSync.Result connected = ExternalDeviceSync.reconcile(board, List.of(uib));
        check(connected.connected() == 1 && placed.externalDeviceState == ExternalDeviceState.CONNECTED,
                "explicitly placed stable id binds to discovery");

        ExternalDeviceSync.Result disconnected = ExternalDeviceSync.reconcile(board, List.of());
        check(disconnected.disconnected() == 1 && placed.externalDeviceState == ExternalDeviceState.DISCONNECTED,
                "missing endpoint becomes DISCONNECTED instead of disappearing");
        check(board.externalDeviceNodes().size() == 1 && placed.id == originalNodeId,
                "disconnect retains the same schematic DEVICE node");
        check(placed.x == 222 && placed.y == 96, "disconnect preserves schematic placement");

        ExternalDeviceSync.Result reconnected = ExternalDeviceSync.reconcile(board, List.of(
                new ExternalDeviceDescriptor("uib-123", ExternalDeviceType.UIB, "world", 30, 70, 40)));
        check(reconnected.connected() == 1 && board.externalDeviceNodes().getFirst().id == originalNodeId,
                "same stable id reconnects the existing node");
        check(placed.externalDeviceState == ExternalDeviceState.CONNECTED && placed.externalDeviceX == 30,
                "reconnect refreshes physical location without replacing the node");
    }

    private static void snapshotChecks() {
        CircuitDocument board = new CircuitDocument();
        EditorNode device = board.addNode(NodeKind.EXTERNAL_DEVICE, 12, 18);
        device.configureExternalDevice(ExternalDeviceType.STORAGE, "disk-a", ExternalDeviceState.DISCONNECTED, "dim", 4, 5, 6);
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
        check(old.externalDeviceState == ExternalDeviceState.UNKNOWN, "legacy display remains unbound until the user places/binds a real endpoint");
    }

    private static void checkPorts(List<PortSpec> actual, List<ExpectedPort> expected, String owner) {
        check(actual.size() == expected.size(), owner + " port count");
        for (int i = 0; i < expected.size(); i++) {
            ExpectedPort want = expected.get(i);
            PortSpec got = actual.get(i);
            check(got.name().equals(want.name) && got.width() == want.width,
                    owner + " port " + i + " is " + want.name + " [" + want.width + "]");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record ExpectedPort(String name, int width) {}
}
