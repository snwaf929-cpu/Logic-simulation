package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.interconnect.CircuitProgramRuntime;

/**
 * Structural adapter for DISPLAY boards whose RESET is electrically tied to a provably static LOW signal.
 *
 * <p>The high-rate RANDOM display engines historically required RESET to be literally unwired. Electrically, an
 * unwired DISPLAY reset is a floating LOW, so a routed LOW constant is equivalent. The editor may route that constant
 * through BUS, SPLITTER, MERGER, BUS_SLICE, NET_LABEL or branch metadata before it reaches the DISPLAY. Looking only at
 * the immediate source node therefore rejects valid boards. This adapter proves the actual compiled signal identity:
 * the DISPLAY RESET signal must alias a LOW bit of an ordinary non-clock/non-random CONSTANT and must currently read
 * LOW. Passive routing nodes preserve the same compiled Signal object/id, so this proof survives all of those routes
 * without treating dynamic INPUT/CLOCK/RANDOM/NAND/custom-chip sources as static.</p>
 *
 * <p>After proof, only the presentation/model RESET wire is temporarily hidden while the strict fast-path compiler is
 * asked to build its plan. The immutable compiled runtime is untouched and the real wire is restored in a finally
 * block. The saved BOARD is never modified.</p>
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
        StaticLowProof proof = proveStaticLow(runtime, board, display, resetWire);
        if (!proof.proven()) {
            return new RandomDisplayNetworkFastPath.CompileResult(null, "display-reset-not-static-low:" + proof.detail());
        }

        int index = board.wires.indexOf(resetWire);
        if (index < 0) return direct;
        board.wires.remove(index);
        try {
            RandomDisplayNetworkFastPath.CompileResult compatible = RandomDisplayNetworkFastPath.compile(
                    runtime, deviceIndex, displayWidth, displayHeight
            );
            if (!compatible.active()) return compatible;
            return new RandomDisplayNetworkFastPath.CompileResult(
                    compatible.plan(),
                    "active-static-low-reset:" + proof.detail()
            );
        } finally {
            board.wires.add(Math.min(index, board.wires.size()), resetWire);
        }
    }

    private static StaticLowProof proveStaticLow(
            CircuitProgramRuntime runtime,
            CircuitDocument board,
            EditorNode display,
            WireConnection resetWire
    ) {
        CompiledCircuit compiled = runtime.compiled();
        Signal reset = compiled.inputSignal(CompiledCircuit.ROOT_SCOPE, display.id, 4, 0);
        if (reset == null) return new StaticLowProof(false, "compiled-reset-missing");
        if (compiled.simulator().isHighFast(reset.id())) {
            return new StaticLowProof(false, "reset-currently-high:signal=" + reset.id());
        }

        // Passive routing is compiled as signal aliasing. If RESET shares the exact signal id with a LOW bit from an
        // ordinary CONSTANT, it cannot change during runtime and is safe to treat exactly like an unwired LOW reset.
        for (EditorNode source : board.nodes) {
            if (source == null || source.kind != NodeKind.CONSTANT || source.clockSource || source.randomSource) continue;
            Signal[] outputs = compiled.outputSignals(CompiledCircuit.ROOT_SCOPE, source.id, 0);
            if (outputs == null) continue;
            for (int bit = 0; bit < outputs.length; bit++) {
                if (outputs[bit] == null || outputs[bit].id() != reset.id()) continue;
                boolean high = bit < 64 && ((source.constantValue >>> bit) & 1L) != 0L;
                if (!high) {
                    return new StaticLowProof(
                            true,
                            "constant-node=" + source.id + ":bit=" + bit + ":signal=" + reset.id()
                    );
                }
                return new StaticLowProof(
                        false,
                        "aliases-high-constant:node=" + source.id + ":bit=" + bit + ":signal=" + reset.id()
                );
            }
        }

        EditorNode immediate = nodeById(board, resetWire.sourceNodeId());
        String source = immediate == null
                ? "missing-node-" + resetWire.sourceNodeId()
                : immediate.kind + "-node-" + immediate.id + "-port-" + resetWire.sourcePort();
        return new StaticLowProof(false, "source=" + source + ":signal=" + reset.id());
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

    private record StaticLowProof(boolean proven, String detail) {}
}
