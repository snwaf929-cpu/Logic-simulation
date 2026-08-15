package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ChipDeletionAccess;
import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Adds deletion without exposing the library's backing config paths to editor UI code. */
@Mixin(ClientChipLibrary.class)
public abstract class ClientChipLibraryDeletionMixin implements ChipDeletionAccess {
    @Invoker("file")
    protected abstract Path logic$file(String name);

    @Invoker("saveLayout")
    protected abstract void logic$saveLayout() throws IOException;

    @Override
    public List<String> logic$dependentsOf(String chipName) throws IOException {
        ClientChipLibrary library = (ClientChipLibrary) (Object) this;
        String canonical = logic$canonical(library, chipName);
        if (canonical == null) throw new IOException("Chip does not exist: " + chipName);

        List<String> dependents = new ArrayList<>();
        for (String candidate : library.names()) {
            if (candidate.equalsIgnoreCase(canonical)) continue;
            ChipDefinition definition = library.load(candidate);
            if (definition == null || definition.circuit == null || definition.circuit.nodes == null) continue;
            for (EditorNode node : definition.circuit.nodes) {
                if (node != null && node.kind == NodeKind.CUSTOM_CHIP
                        && node.chipName != null && node.chipName.equalsIgnoreCase(canonical)) {
                    dependents.add(candidate);
                    break;
                }
            }
        }
        dependents.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(dependents);
    }

    @Override
    public void logic$deleteChip(String chipName) throws IOException {
        ClientChipLibrary library = (ClientChipLibrary) (Object) this;
        String canonical = logic$canonical(library, chipName);
        if (canonical == null) throw new IOException("Chip does not exist: " + chipName);

        List<String> dependents = logic$dependentsOf(canonical);
        if (!dependents.isEmpty()) {
            String shown = String.join(", ", dependents.subList(0, Math.min(4, dependents.size())));
            if (dependents.size() > 4) shown += " +" + (dependents.size() - 4) + " more";
            throw new IOException("Cannot delete " + canonical + "; used by " + shown);
        }

        Path file = logic$file(canonical);
        if (!Files.deleteIfExists(file)) throw new IOException("Chip file does not exist: " + canonical);
        library.reload();
        logic$saveLayout();
    }

    private static String logic$canonical(ClientChipLibrary library, String name) {
        if (name == null || name.isBlank()) return null;
        for (String candidate : library.names()) if (candidate.equalsIgnoreCase(name.trim())) return candidate;
        return null;
    }
}
