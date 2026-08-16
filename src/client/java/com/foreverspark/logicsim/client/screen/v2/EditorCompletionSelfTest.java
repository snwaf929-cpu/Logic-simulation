package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.CircuitHardwareSignature;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceState;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireLayer;

import java.util.List;

/** Consolidated regression for the final editor/persistence cleanup pass. */
public final class EditorCompletionSelfTest {
    private EditorCompletionSelfTest() {}

    public static void main(String[] args) {
        chipPortOrderMigratesAndPersists();
        snapshotPreservesRuntimeMetadata();
        hardwareSignatureIgnoresCadOnlyChanges();
        hardwareSignatureTracksElectricalChanges();
        System.out.println("Editor completion metadata/persistence self-test: PASS | chipPortOrder=stable snapshotWorkers=preserved cadRestart=false electricalRestart=true");
    }

    private static void chipPortOrderMigratesAndPersists() {
        CircuitDocument chip = new CircuitDocument();
        EditorNode inA = chip.addNode(NodeKind.INPUT, 0, 0); inA.label = "A"; inA.width = 8;
        EditorNode inB = chip.addNode(NodeKind.INPUT, 0, 24); inB.label = "B"; inB.width = 16;
        EditorNode outX = chip.addNode(NodeKind.OUTPUT, 120, 0); outX.label = "X";
        EditorNode outY = chip.addNode(NodeKind.OUTPUT, 120, 24); outY.label = "Y";
        check(inA.chipPortOrder == -1 && inB.chipPortOrder == -1, "new/legacy terminals begin unassigned");
        chip.normalize();
        check(inA.chipPortOrder == 0 && inB.chipPortOrder == 1, "legacy INPUT order migrates from node id");
        check(outX.chipPortOrder == 0 && outY.chipPortOrder == 1, "legacy OUTPUT order migrates from node id");

        int swap = inA.chipPortOrder; inA.chipPortOrder = inB.chipPortOrder; inB.chipPortOrder = swap;
        swap = outX.chipPortOrder; outX.chipPortOrder = outY.chipPortOrder; outY.chipPortOrder = swap;
        chip.normalize();
        check(chip.inputNodes().getFirst().id == inB.id && chip.inputNodes().getLast().id == inA.id,
                "explicit INPUT port order survives normalize independently of node id");
        check(chip.outputNodes().getFirst().id == outY.id && chip.outputNodes().getLast().id == outX.id,
                "explicit OUTPUT port order survives normalize independently of node id");

        ChipDefinition definition = new ChipDefinition("ORDER_TEST", chip);
        check(definition.inputPorts().getFirst().name().equals("B") && definition.inputPorts().getFirst().width() == 16,
                "public custom-CHIP input metadata follows explicit port order");
        check(definition.outputPorts().getFirst().name().equals("Y"),
                "public custom-CHIP output metadata follows explicit port order");
    }

    private static void snapshotPreservesRuntimeMetadata() {
        CircuitDocument board = new CircuitDocument();
        board.simulationWorkers = 4;
        EditorNode a = board.addNode(NodeKind.INPUT, 0, 0);
        EditorNode b = board.addNode(NodeKind.INPUT, 0, 24);
        board.normalize();
        a.chipPortOrder = 1;
        b.chipPortOrder = 0;
        board.normalize();

        CircuitDocument copy = EditorDocumentSnapshot.copy(board);
        check(copy.simulationWorkers == 4, "deep editor snapshot preserves per-BOARD worker budget");
        check(copy.inputNodes().getFirst().id == b.id, "deep editor snapshot preserves reusable CHIP port order");
        check(EditorDocumentSnapshot.same(board, copy), "snapshot equality includes worker budget and CHIP port order");
        copy.simulationWorkers = 2;
        check(!EditorDocumentSnapshot.same(board, copy), "worker-budget changes create undo/redo history entries");
    }

    private static void hardwareSignatureIgnoresCadOnlyChanges() {
        CircuitDocument board = baseBoard();
        String before = CircuitHardwareSignature.of(board);
        EditorNode input = board.node(1);
        input.x += 144;
        input.y += 72;
        input.locked = true;
        board.wires.getFirst().setRoutePoints(List.of(new RoutePoint(30, 0), new RoutePoint(30, 60)));
        board.wires.getFirst().setLayer(WireLayer.BACK);
        board.wires.getFirst().setViaRouteIndices(List.of(0));
        String after = CircuitHardwareSignature.of(board);
        check(before.equals(after), "layout/lock/route/layer/via edits must not restart running electrical hardware");
    }

    private static void hardwareSignatureTracksElectricalChanges() {
        assertElectricalChange(board -> board.simulationWorkers = 3, "worker budget");
        assertElectricalChange(board -> board.node(1).width = 16, "terminal width");
        assertElectricalChange(board -> board.node(1).label = "DATA_RENAMED", "electrical/net-facing label");
        assertElectricalChange(board -> {
            EditorNode slice = board.addNode(NodeKind.BUS_SLICE, 180, 0);
            slice.width = 16;
            slice.slices = new java.util.ArrayList<>(List.of(new BusSliceOutput("OP", 12, 4), new BusSliceOutput("ARG", 0, 12)));
        }, "BUS_SLICE ranges");
        reusablePortOrderChangesSignature();
        assertElectricalChange(board -> {
            EditorNode socket = board.addNode(NodeKind.BUS, 200, 20);
            socket.width = 32;
            socket.configureBoardSocket("CPU_BUS", PortDirection.OUTPUT, 2);
            socket.interfaceId = "cpu-bus-stable";
        }, "BOARD socket identity/order/direction");
        assertElectricalChange(board -> {
            EditorNode device = board.addNode(NodeKind.EXTERNAL_DEVICE, 240, 20);
            device.configureExternalDevice(ExternalDeviceType.DISPLAY, "display-42", ExternalDeviceState.CONNECTED,
                    "minecraft:overworld", 10, 64, 20);
        }, "external device binding/location/type");

        CircuitDocument deviceStateOnly = baseBoard();
        EditorNode device = deviceStateOnly.addNode(NodeKind.EXTERNAL_DEVICE, 240, 20);
        device.configureExternalDevice(ExternalDeviceType.DISPLAY, "display-42", ExternalDeviceState.CONNECTED,
                "minecraft:overworld", 10, 64, 20);
        String connected = CircuitHardwareSignature.of(deviceStateOnly);
        device.externalDeviceState = ExternalDeviceState.UNKNOWN;
        check(connected.equals(CircuitHardwareSignature.of(deviceStateOnly)),
                "transient CONNECTED/UNKNOWN discovery state does not restart identical device binding");
    }

    private static void reusablePortOrderChangesSignature() {
        CircuitDocument board = baseBoard();
        EditorNode second = board.addNode(NodeKind.INPUT, 0, 30);
        second.label = "ADDR";
        second.width = 8;
        board.normalize();
        String before = CircuitHardwareSignature.of(board);
        EditorNode first = board.node(1);
        int swap = first.chipPortOrder;
        first.chipPortOrder = second.chipPortOrder;
        second.chipPortOrder = swap;
        String after = CircuitHardwareSignature.of(board);
        check(!before.equals(after), "reusable CHIP port order must trigger running BOARD reinstall");
    }

    private static CircuitDocument baseBoard() {
        CircuitDocument board = new CircuitDocument();
        EditorNode input = board.addNode(NodeKind.INPUT, 0, 0);
        input.label = "DATA";
        input.width = 8;
        EditorNode output = board.addNode(NodeKind.OUTPUT, 120, 0);
        output.label = "OUT";
        output.width = 8;
        board.connect(input.id, 0, output.id, 0);
        board.normalize();
        return board;
    }

    private static void assertElectricalChange(java.util.function.Consumer<CircuitDocument> mutation, String name) {
        CircuitDocument board = baseBoard();
        String before = CircuitHardwareSignature.of(board);
        mutation.accept(board);
        String after = CircuitHardwareSignature.of(board);
        check(!before.equals(after), name + " must trigger running BOARD reinstall");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
