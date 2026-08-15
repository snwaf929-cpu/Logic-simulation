package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

/**
 * Structural adapter for legacy DISPLAY boards that explicitly tie RESET to CONSTANT 0.
 *
 * <p>RandomDisplayNetworkFastPath historically required RESET to be literally unwired. Electrically, an unwired
 * DISPLAY reset is a floating LOW in this compiler, so a direct ordinary CONSTANT 0 is equivalent. This adapter proves
 * that exact case, temporarily hides only the presentation/model wire while the immutable compiled runtime plan is
 * inspected, and restores the board in a finally block. No simulator signal or saved circuit is mutated.</p>
 */
public final class RandomDisplayNetworkResetCompat {
    private RandomDisplayNetworkResetCompat() {}

    public static RandomDisplayNetworkFastPath.CompileResult compile(
            CircuitProgramRuntime runtime,
            int deviceIndex,
            int displayWidth,
            int displayHeight
    ) {
        RandomDisplayNetworkFastPath.CompileResult direct = RandomDisplayNetworkFastPath.compile(
                runtime, deviceIndex, displayWidth, displayHeight
        );
        if (direct.active() || !"display-reset-wired".equals(direct.reason())) return direct;
        if (runtime == null || deviceIndex < 0 || deviceIndex >= runtime.externalDeviceCount()) return direct;
        if (runtime.externalDeviceType(deviceIndex) != ExternalDeviceType.DISPLAY) return direct;

        CircuitDocument board = runtime.program().root.circuit;
        if (board == null) return direct;
        EditorNode display = findDisplay(runtime, board, deviceIndex);
        if (display == null) return direct;

        WireConnection resetWire = resetWire(board, display.id);
        if (resetWire == null) return direct;
        if (!isDirectConstantLow(board, resetWire)) {
            return new RandomDisplayNetworkFastPath.CompileResult(null, "display-reset-not-static-low");
        }

        int index = board.wires.indexOf(resetWire);
        if (index < 0) return direct;
        board.wires.remove(index);
        try {
            RandomDisplayNetworkFastPath.CompileResult compatible = RandomDisplayNetworkFastPath.compile(
                    runtime, deviceIndex, displayWidth, displayHeight
            );
            if (!compatible.active()) return compatible;
            return new RandomDisplayNetworkFastPath.CompileResult(compatible.plan(), "active-static-low-reset");
        } finally {
            board.wires.add(Math.min(index, board.wires.size()), resetWire);
        }
    }

    private static EditorNode findDisplay(CircuitProgramRuntime runtime, CircuitDocument board, int deviceIndex) {
        String id = runtime.externalDeviceId(deviceIndex);
        EditorNode found = null;
        for (EditorNode node : board.externalDeviceNodes()) {
            if (node.externalDeviceType != ExternalDeviceType.DISPLAY) continue;
            if (!id.equals(node.externalDeviceId)) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    private static WireConnection resetWire(CircuitDocument board, int displayNodeId) {
        WireConnection found = null;
        for (WireConnection wire : board.wires) {
            if (wire.targetNodeId() != displayNodeId || wire.targetPort() != 4) continue;
            if (found != null) return null;
            found = wire;
        }
        return found;
    }

    private static boolean isDirectConstantLow(CircuitDocument board, WireConnection wire) {
        if (wire.sourcePort() != 0) return false;
        for (EditorNode source : board.nodes) {
            if (source == null || source.id != wire.sourceNodeId()) continue;
            return source.kind == NodeKind.CONSTANT
                    && !source.clockSource
                    && !source.randomSource
                    && source.width == 1
                    && (source.constantValue & 1L) == 0L;
        }
        return false;
    }
}
