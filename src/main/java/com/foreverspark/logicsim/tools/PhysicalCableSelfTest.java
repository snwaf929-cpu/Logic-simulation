package com.foreverspark.logicsim.tools;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.CircuitDeviceProfile;
import com.foreverspark.logicsim.interconnect.InterconnectDevice;
import com.foreverspark.logicsim.interconnect.PhysicalPortBinding;

public final class PhysicalCableSelfTest {
    private PhysicalCableSelfTest() {}

    public static void main(String[] args) {
        check(CableKind.SIGNAL.supportsWidth(1), "1-bit signal wire supported");
        check(!CableKind.SIGNAL.supportsWidth(2), "signal wire rejects 2-bit");
        for (int width : new int[]{2, 4, 8, 16, 32}) {
            check(CableKind.BUS.supportsWidth(width), width + "-bit bus cable supported");
        }
        check(!CableKind.BUS.supportsWidth(1), "bus rejects 1-bit; use signal wire");
        check(!CableKind.BUS.supportsWidth(64), "physical bus currently stops at 32-bit");
        testNamedCircuitPortsBecomeWorldPorts();
        System.out.println("Physical cable widths + circuit world-port binding self-test: PASS");
    }

    private static void testNamedCircuitPortsBecomeWorldPorts() {
        CircuitDocument document = new CircuitDocument();
        EditorNode address = document.addNode(NodeKind.INPUT, 0, 0);
        address.label = "ADDRESS";
        address.width = 16;
        EditorNode reset = document.addNode(NodeKind.INPUT, 0, 60);
        reset.label = "RESET";
        reset.width = 1;
        EditorNode data = document.addNode(NodeKind.OUTPUT, 200, 0);
        data.label = "DATA";
        data.width = 32;

        InterconnectDevice device = CircuitDeviceProfile.fromChip("cpu", new ChipDefinition("CPU", document));
        check(device.inputs().size() == 2, "world CPU exposes its two named inputs");
        check(device.outputs().size() == 1, "world CPU exposes its named output");

        PhysicalPortBinding addressSocket = new PhysicalPortBinding(device.inputs().get(0));
        PhysicalPortBinding resetSocket = new PhysicalPortBinding(device.inputs().get(1));
        PhysicalPortBinding dataSocket = new PhysicalPortBinding(device.outputs().get(0));

        check(addressSocket.accepts(CableKind.BUS, 16), "ADDRESS accepts 16-bit bus cable");
        check(!addressSocket.accepts(CableKind.BUS, 8), "ADDRESS rejects wrong bus width");
        check(resetSocket.accepts(CableKind.SIGNAL, 1), "RESET accepts normal 1-bit signal wire");
        check(dataSocket.accepts(CableKind.BUS, 32), "DATA accepts 32-bit bus cable");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
