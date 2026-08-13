package com.foreverspark.logicsim.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.interconnect.CircuitProgram;
import com.foreverspark.logicsim.network.ProgramCircuitPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ClientProgramUploader {
    private static final int MAX_DEPENDENCIES = 256;

    private ClientProgramUploader() {}

    public static void upload(BlockPos target, String rootChipName, ClientChipLibrary library) throws IOException {
        if (target == null || rootChipName == null || rootChipName.isBlank()) return;
        ChipDefinition root = library.load(rootChipName);
        LinkedHashMap<String, ChipDefinition> dependencies = new LinkedHashMap<>();
        collect(root, library, dependencies, new HashSet<>());
        CircuitProgram program = new CircuitProgram(root, dependencies);
        String json = program.toJson();
        if (json.length() > ProgramCircuitPayload.MAX_JSON_LENGTH) {
            throw new IOException("Program is too large for a Circuit Block");
        }
        ClientPlayNetworking.send(new ProgramCircuitPayload(target, json));
    }

    private static void collect(
            ChipDefinition definition,
            ClientChipLibrary library,
            Map<String, ChipDefinition> dependencies,
            Set<String> visiting
    ) throws IOException {
        String owner = definition.name == null ? "" : definition.name;
        if (!visiting.add(owner.toLowerCase(java.util.Locale.ROOT))) return;
        for (EditorNode node : definition.circuit.nodes) {
            if (node.kind != NodeKind.CUSTOM_CHIP || node.chipName == null || node.chipName.isBlank()) continue;
            String dependencyName = node.chipName.trim();
            if (dependencies.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(dependencyName))) continue;
            if (dependencies.size() >= MAX_DEPENDENCIES) throw new IOException("Too many nested chip dependencies");
            ChipDefinition dependency = library.load(dependencyName);
            dependencies.put(dependency.name, dependency);
            collect(dependency, library, dependencies, visiting);
        }
        visiting.remove(owner.toLowerCase(java.util.Locale.ROOT));
    }
}
