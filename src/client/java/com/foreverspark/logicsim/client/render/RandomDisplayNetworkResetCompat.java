package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

/**
 * Adapter that lets the RANDOM DISPLAY bulk compiler accept a real wired RESET input.
 *
 * <p>The packed RANDOM engine does not use RESET to compute X/Y/COLOR or RANDOM state, so RESET does not need to
 * disable compilation. The strict compiler still rejects a RESET wire to keep its closed-world proof simple. This
 * adapter temporarily hides only that presentation/model wire while the immutable already-compiled runtime is
 * inspected, then restores it immediately. The real compiled RESET signal remains intact and is monitored by the
 * worker mixin with normal rising-edge CLEAR semantics.</p>
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

        int resetSignalId = resetSignalId(runtime, deviceIndex);
        if (resetSignalId < 0) {
            return new RandomDisplayNetworkFastPath.CompileResult(null, "display-reset-signal-unresolved");
        }

        int index = board.wires.indexOf(resetWire);
        if (index < 0) return direct;
        board.wires.remove(index);
        try {
            RandomDisplayNetworkFastPath.CompileResult compatible = RandomDisplayNetworkFastPath.compile(
                    runtime, deviceIndex, displayWidth, displayHeight
            );
            if (!compatible.active()) return compatible;
            EditorNode immediate = nodeById(board, resetWire.sourceNodeId());
            String source = immediate == null
                    ? "missing-node-" + resetWire.sourceNodeId()
                    : immediate.kind + "-node-" + immediate.id + "-port-" + resetWire.sourcePort();
            return new RandomDisplayNetworkFastPath.CompileResult(
                    compatible.plan(),
                    "active-dynamic-reset:source=" + source + ":signal=" + resetSignalId
            );
        } finally {
            board.wires.add(Math.min(index, board.wires.size()), resetWire);
        }
    }

    /** Returns the real compiled one-bit RESET signal. It remains live even while the model wire is hidden for proof. */
    public static int resetSignalId(CircuitProgramRuntime runtime, int deviceIndex) {
        if (runtime == null || deviceIndex < 0 || deviceIndex >= runtime.externalDeviceCount()) return -1;
        if (runtime.externalDeviceType(deviceIndex) != ExternalDeviceType.DISPLAY) return -1;
        CircuitDocument board = runtime.program().root.circuit;
        if (board == null) return -1;
        EditorNode display = findDisplay(runtime, board, deviceIndex);
        if (display == null) return -1;
        Signal reset = runtime.compiled().inputSignal(CompiledCircuit.ROOT_SCOPE, display.id, 4, 0);
        return reset == null ? -1 : reset.id();
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

    private static EditorNode nodeById(CircuitDocument board, int nodeId) {
        for (EditorNode node : board.nodes) {
            if (node != null && node.id == nodeId) return node;
        }
        return null;
    }
}
