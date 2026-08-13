package com.foreverspark.logicsim.client.chip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-side chip library for the editor milestone. */
public final class ClientChipLibrary implements ChipLookup {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory = FabricLoader.getInstance().getConfigDir()
            .resolve("logic-simulation")
            .resolve("chips");

    public void save(String name, CircuitDocument circuit) throws IOException {
        String normalized = validateName(name);
        Files.createDirectories(directory);
        ChipDefinition definition = new ChipDefinition(normalized, copyDocument(circuit));
        try (Writer writer = Files.newBufferedWriter(file(normalized), StandardCharsets.UTF_8)) {
            GSON.toJson(definition, writer);
        }
    }

    public ChipDefinition load(String name) throws IOException {
        String normalized = validateName(name);
        Path path = file(normalized);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Chip does not exist: " + normalized);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ChipDefinition definition = GSON.fromJson(reader, ChipDefinition.class);
            if (definition == null || definition.circuit == null) {
                throw new IOException("Invalid chip file: " + normalized);
            }
            definition.circuit.normalize();
            if (definition.name == null || definition.name.isBlank()) {
                definition.name = normalized;
            }
            return definition;
        } catch (RuntimeException exception) {
            throw new IOException("Could not read chip " + normalized + ": " + exception.getMessage(), exception);
        }
    }

    @Override
    public ChipDefinition find(String name) {
        try {
            return load(name);
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    public List<String> names() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".logicchip.json"))
                    .forEach(path -> {
                        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            ChipDefinition definition = GSON.fromJson(reader, ChipDefinition.class);
                            if (definition != null && definition.name != null && !definition.name.isBlank()) {
                                names.add(definition.name);
                            }
                        } catch (Exception ignored) {
                            // A corrupt chip should not prevent the editor from opening.
                        }
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    public CircuitDocument copyDocument(CircuitDocument circuit) {
        CircuitDocument copy = GSON.fromJson(GSON.toJson(circuit), CircuitDocument.class);
        copy.normalize();
        return copy;
    }

    private Path file(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve(safe + ".logicchip.json");
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Chip name is required");
        }
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Chip name is required");
        }
        if (normalized.length() > 48) {
            throw new IllegalArgumentException("Chip name is too long (max 48)");
        }
        return normalized;
    }
}
