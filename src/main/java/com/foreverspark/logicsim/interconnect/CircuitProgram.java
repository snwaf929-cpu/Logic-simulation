package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Self-contained program installed into one physical Circuit Block. */
public final class CircuitProgram implements ChipLookup {
    private static final Gson GSON = new GsonBuilder().create();

    public int formatVersion = 1;
    public ChipDefinition root = new ChipDefinition();
    public Map<String, ChipDefinition> dependencies = new LinkedHashMap<>();

    public CircuitProgram() {}

    public CircuitProgram(ChipDefinition root, Map<String, ChipDefinition> dependencies) {
        this.root = root == null ? new ChipDefinition() : root;
        if (dependencies != null) this.dependencies.putAll(dependencies);
        normalize();
    }

    public void normalize() {
        if (root == null) root = new ChipDefinition();
        root.normalize();
        if (dependencies == null) dependencies = new LinkedHashMap<>();
        LinkedHashMap<String, ChipDefinition> clean = new LinkedHashMap<>();
        for (Map.Entry<String, ChipDefinition> entry : dependencies.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            ChipDefinition definition = entry.getValue();
            definition.normalize();
            clean.put(definition.name, definition);
        }
        dependencies = clean;
        formatVersion = Math.max(formatVersion, 1);
    }

    @Override
    public ChipDefinition find(String name) {
        if (name == null || name.isBlank()) return null;
        if (root.name != null && root.name.equalsIgnoreCase(name)) return root;
        for (Map.Entry<String, ChipDefinition> entry : dependencies.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    public CompiledCircuit compile() {
        normalize();
        return CircuitCompiler.compile(root.circuit, this);
    }

    public List<PortSpec> inputPorts() { return root.inputPorts(); }
    public List<PortSpec> outputPorts() { return root.outputPorts(); }

    public String toJson() {
        normalize();
        return GSON.toJson(this);
    }

    /**
     * Parses and normalizes a saved program. Compilation/validation is deliberately performed by
     * CircuitProgramRuntime so installing a physical Circuit Block compiles the program exactly once.
     */
    public static CircuitProgram fromJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Circuit program is empty");
        CircuitProgram program = GSON.fromJson(json, CircuitProgram.class);
        if (program == null) throw new IllegalArgumentException("Circuit program is invalid");
        program.normalize();
        return program;
    }
}
