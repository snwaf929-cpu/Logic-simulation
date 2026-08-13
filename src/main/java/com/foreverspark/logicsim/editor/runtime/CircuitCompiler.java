package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flattens an editor document into the NAND-only event-driven core.
 * Splitters and mergers are structural aliases, not hidden logic gates.
 * Custom chips recursively flatten to the NANDs the player actually built.
 */
public final class CircuitCompiler {
    private CircuitCompiler() {
    }

    public static CompiledCircuit compile(CircuitDocument document, ChipLookup chips) {
        if (document == null) {
            throw new CircuitCompileException("Circuit document is null");
        }
        document.normalize();
        LogicCircuit circuit = new LogicCircuit();
        Map<Integer, Signal[]> rootInputs = new HashMap<>();
        BuildContext root = new BuildContext(
                document,
                chips == null ? ChipLookup.empty() : chips,
                circuit,
                "ROOT",
                Map.of(),
                rootInputs,
                Set.of()
        );

        try {
            for (EditorNode node : document.nodes) {
                List<PortSpec> inputs = NodePorts.inputs(node, root.chips);
                for (int port = 0; port < inputs.size(); port++) {
                    root.resolveInput(node, port);
                }
                List<PortSpec> outputs = NodePorts.outputs(node, root.chips);
                for (int port = 0; port < outputs.size(); port++) {
                    root.resolveOutput(node, port);
                }
            }
        } catch (CircuitCompileException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CircuitCompileException(exception.getMessage(), exception);
        }

        CircuitSimulator simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        long settleBudget = Math.max(1_024L, circuit.gates().size() * 64L + 64L);
        simulator.runUntilStable(settleBudget);
        return new CompiledCircuit(simulator, rootInputs, root.inputCache, root.outputCache, settleBudget);
    }

    private static final class BuildContext {
        private final CircuitDocument document;
        private final ChipLookup chips;
        private final LogicCircuit circuit;
        private final String path;
        private final Map<Integer, Signal[]> inputOverrides;
        private final Map<Integer, Signal[]> rootInputs;
        private final Set<String> chipStack;
        private final Map<NodePortKey, Signal[]> inputCache = new HashMap<>();
        private final Map<NodePortKey, Signal[]> outputCache = new HashMap<>();
        private final Set<Integer> realizedNands = new HashSet<>();
        private final Map<Integer, List<Signal[]>> customOutputCache = new HashMap<>();

        private BuildContext(
                CircuitDocument document,
                ChipLookup chips,
                LogicCircuit circuit,
                String path,
                Map<Integer, Signal[]> inputOverrides,
                Map<Integer, Signal[]> rootInputs,
                Set<String> chipStack
        ) {
            this.document = document;
            this.chips = chips;
            this.circuit = circuit;
            this.path = path;
            this.inputOverrides = inputOverrides;
            this.rootInputs = rootInputs;
            this.chipStack = chipStack;
        }

        private Signal[] resolveInput(EditorNode node, int port) {
            NodePortKey key = new NodePortKey(node.id, port);
            Signal[] cached = inputCache.get(key);
            if (cached != null) {
                return cached;
            }

            List<PortSpec> inputPorts = NodePorts.inputs(node, chips);
            if (port < 0 || port >= inputPorts.size()) {
                throw new CircuitCompileException("Invalid input port " + port + " on node " + node.id);
            }
            int width = inputPorts.get(port).width();

            WireConnection matching = null;
            for (WireConnection wire : document.wires) {
                if (wire.targetNodeId() == node.id && wire.targetPort() == port) {
                    if (matching != null) {
                        throw new CircuitCompileException("Multiple wires drive node " + node.id + " input " + port);
                    }
                    matching = wire;
                }
            }

            Signal[] signals;
            if (matching == null) {
                signals = createSignals(path + "/NODE" + node.id + "/IN" + port + "/FLOAT", width);
            } else {
                EditorNode sourceNode = document.node(matching.sourceNodeId());
                List<PortSpec> sourcePorts = NodePorts.outputs(sourceNode, chips);
                if (matching.sourcePort() < 0 || matching.sourcePort() >= sourcePorts.size()) {
                    throw new CircuitCompileException("Invalid source port on node " + sourceNode.id);
                }
                int sourceWidth = sourcePorts.get(matching.sourcePort()).width();
                if (sourceWidth != width) {
                    throw new CircuitCompileException(
                            "Width mismatch: node " + sourceNode.id + " output is " + sourceWidth
                                    + "-bit but node " + node.id + " input is " + width + "-bit"
                    );
                }
                signals = resolveOutput(sourceNode, matching.sourcePort());
            }
            inputCache.put(key, signals);
            return signals;
        }

        private Signal[] resolveOutput(EditorNode node, int port) {
            NodePortKey key = new NodePortKey(node.id, port);
            Signal[] cached = outputCache.get(key);
            if (cached != null) {
                return cached;
            }

            List<PortSpec> outputPorts = NodePorts.outputs(node, chips);
            if (port < 0 || port >= outputPorts.size()) {
                throw new CircuitCompileException("Invalid output port " + port + " on node " + node.id);
            }

            Signal[] result = switch (node.kind) {
                case INPUT -> resolveRootOrOverriddenInput(node);
                case NAND -> resolveNand(node, key);
                case SPLITTER -> {
                    Signal[] bus = resolveInput(node, 0);
                    yield new Signal[]{bus[port]};
                }
                case MERGER -> {
                    Signal[] merged = new Signal[node.width];
                    for (int bit = 0; bit < node.width; bit++) {
                        merged[bit] = resolveInput(node, bit)[0];
                    }
                    yield merged;
                }
                case CUSTOM_CHIP -> resolveCustomOutputs(node).get(port);
                case OUTPUT -> throw new CircuitCompileException("Output node " + node.id + " has no output ports");
            };

            outputCache.putIfAbsent(key, result);
            return outputCache.get(key);
        }

        private Signal[] resolveRootOrOverriddenInput(EditorNode node) {
            Signal[] override = inputOverrides.get(node.id);
            if (override != null) {
                return override;
            }
            Signal[] signals = outputCache.computeIfAbsent(
                    new NodePortKey(node.id, 0),
                    ignored -> createSignals(path + "/INPUT" + node.id, node.width)
            );
            rootInputs.putIfAbsent(node.id, signals);
            return signals;
        }

        private Signal[] resolveNand(EditorNode node, NodePortKey key) {
            Signal[] out = outputCache.computeIfAbsent(
                    key,
                    ignored -> createSignals(path + "/NAND" + node.id + "/OUT", 1)
            );
            if (realizedNands.add(node.id)) {
                Signal a = resolveInput(node, 0)[0];
                Signal b = resolveInput(node, 1)[0];
                circuit.nand(path + "/NAND" + node.id, a, b, out[0]);
            }
            return out;
        }

        private List<Signal[]> resolveCustomOutputs(EditorNode node) {
            List<Signal[]> cached = customOutputCache.get(node.id);
            if (cached != null) {
                return cached;
            }
            String chipName = node.chipName == null ? "" : node.chipName.trim();
            if (chipName.isEmpty()) {
                throw new CircuitCompileException("Custom chip node " + node.id + " has no chip name");
            }
            if (chipStack.contains(chipName)) {
                throw new CircuitCompileException("Recursive custom chip reference: " + chipName);
            }
            ChipDefinition definition = chips.find(chipName);
            if (definition == null || definition.circuit == null) {
                throw new CircuitCompileException("Missing custom chip: " + chipName);
            }
            definition.circuit.normalize();

            List<EditorNode> childInputs = definition.circuit.inputNodes();
            List<PortSpec> parentInputs = NodePorts.inputs(node, chips);
            if (childInputs.size() != parentInputs.size()) {
                throw new CircuitCompileException("Custom chip input metadata mismatch: " + chipName);
            }
            Map<Integer, Signal[]> overrides = new HashMap<>();
            for (int i = 0; i < childInputs.size(); i++) {
                overrides.put(childInputs.get(i).id, resolveInput(node, i));
            }

            Set<String> nestedStack = new HashSet<>(chipStack);
            nestedStack.add(chipName);
            BuildContext child = new BuildContext(
                    definition.circuit,
                    chips,
                    circuit,
                    path + "/CHIP" + node.id + "[" + chipName + "]",
                    overrides,
                    rootInputs,
                    Set.copyOf(nestedStack)
            );

            List<Signal[]> outputs = new ArrayList<>();
            for (EditorNode childOutput : definition.circuit.outputNodes()) {
                outputs.add(child.resolveInput(childOutput, 0));
            }
            List<Signal[]> immutable = List.copyOf(outputs);
            customOutputCache.put(node.id, immutable);
            return immutable;
        }

        private Signal[] createSignals(String basePath, int width) {
            Signal[] signals = new Signal[width];
            for (int bit = 0; bit < width; bit++) {
                signals[bit] = circuit.signal(basePath + "[" + bit + "]", LogicValue.UNKNOWN);
            }
            return signals;
        }
    }
}
