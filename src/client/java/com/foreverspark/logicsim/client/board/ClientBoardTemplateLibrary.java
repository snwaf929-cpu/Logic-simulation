package com.foreverspark.logicsim.client.board;

import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistent reusable BOARD templates, deliberately separate from editable boards and CHIP files. */
public final class ClientBoardTemplateLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    public ClientBoardTemplateLibrary() {
        this(FabricLoader.getInstance().getConfigDir().resolve("logic-simulation").resolve("board-templates"));
    }

    /** Alternate storage root used by isolated tooling/tests and future workspace import/export. */
    public ClientBoardTemplateLibrary(Path directory) {
        if (directory == null) throw new IllegalArgumentException("Template directory is required");
        this.directory = directory;
    }

    public List<String> names() {
        if (!Files.isDirectory(directory)) return List.of();
        List<String> result = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        String file = path.getFileName().toString();
                        result.add(file.substring(0, file.length() - 5));
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    public boolean exists(String name) {
        try {
            return Files.isRegularFile(file(validateName(name)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public BoardTemplateDefinition load(String name) throws IOException {
        String normalized = validateName(name);
        Path path = file(normalized);
        if (!Files.isRegularFile(path)) throw new IOException("BOARD template does not exist: " + normalized);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            BoardTemplateDefinition definition = GSON.fromJson(reader, BoardTemplateDefinition.class);
            if (definition == null) throw new IOException("BOARD template file is empty: " + normalized);
            if (definition.name == null || definition.name.isBlank()) definition.name = normalized;
            definition.normalize();
            return definition;
        }
    }

    /**
     * Saves the current physical board as a reusable template. Existing inserted template instances
     * are flattened into ordinary nodes; their nested sockets are internalized so only sockets authored
     * directly on this board become the new template boundary.
     */
    public void save(String name, CircuitDocument board) throws IOException {
        String normalized = validateName(name);
        if (board == null) throw new IllegalArgumentException("Board document is required");
        CircuitDocument copy = copyDocument(board);
        for (EditorNode node : copy.nodes) {
            boolean nestedInstance = node.templateInstanceId > 0;
            node.templateInstanceId = 0;
            node.templateName = "";
            if (nestedInstance && node.boardSocket) {
                node.boardSocket = false;
                node.interfaceId = "";
                node.socketDirection = PortDirection.INPUT;
                node.interfaceOrder = 0;
            }
        }
        copy.nextTemplateInstanceId = 1;
        copy.normalize();
        BoardTemplateDefinition definition = new BoardTemplateDefinition(normalized, copy);
        saveDefinition(definition);
    }

    public void saveDefinition(BoardTemplateDefinition definition) throws IOException {
        if (definition == null) throw new IllegalArgumentException("BOARD template is required");
        definition.normalize();
        String normalized = validateName(definition.name);
        definition.name = normalized;
        Files.createDirectories(directory);
        Path target = file(normalized);
        Path temporary = directory.resolve(normalized + ".json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(definition, writer);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(file(validateName(name)));
    }

    public CircuitDocument copyDocument(CircuitDocument document) {
        if (document == null) return new CircuitDocument();
        CircuitDocument copy = GSON.fromJson(GSON.toJson(document), CircuitDocument.class);
        if (copy == null) copy = new CircuitDocument();
        copy.normalize();
        return copy;
    }

    private Path file(String name) {
        return directory.resolve(name + ".json");
    }

    private static String validateName(String name) {
        if (name == null) throw new IllegalArgumentException("BOARD template name is required");
        String normalized = name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("BOARD template name is required");
        if (normalized.length() > 48) throw new IllegalArgumentException("BOARD template name is too long (max 48)");
        if (normalized.equals(".") || normalized.equals("..")) throw new IllegalArgumentException("Invalid template name");
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0 || normalized.indexOf(':') >= 0
                || normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0 || normalized.indexOf('"') >= 0
                || normalized.indexOf('<') >= 0 || normalized.indexOf('>') >= 0 || normalized.indexOf('|') >= 0) {
            throw new IllegalArgumentException("BOARD template name contains invalid file-name characters");
        }
        return normalized;
    }
}
