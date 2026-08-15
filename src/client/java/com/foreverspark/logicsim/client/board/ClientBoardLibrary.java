package com.foreverspark.logicsim.client.board;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
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

/**
 * Editable top-level board projects.
 *
 * Boards are deliberately separate from reusable chips. A board is a workspace/project that can be
 * reopened and edited later; a chip is a reusable component that can be placed inside another circuit.
 */
public final class ClientBoardLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path boardDirectory;

    public ClientBoardLibrary() {
        this(FabricLoader.getInstance().getConfigDir().resolve("logic-simulation").resolve("boards"));
    }

    ClientBoardLibrary(Path boardDirectory) {
        if (boardDirectory == null) throw new IllegalArgumentException("Board directory is required");
        this.boardDirectory = boardDirectory;
    }

    public List<String> names() {
        if (!Files.isDirectory(boardDirectory)) return List.of();
        List<String> result = new ArrayList<>();
        try (var stream = Files.list(boardDirectory)) {
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
        if (name == null || name.isBlank()) return false;
        try {
            return Files.isRegularFile(file(validateName(name)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public CircuitDocument load(String name) throws IOException {
        String normalized = validateName(name);
        Path path = file(normalized);
        if (!Files.isRegularFile(path)) throw new IOException("Board does not exist: " + normalized);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CircuitDocument document = GSON.fromJson(reader, CircuitDocument.class);
            if (document == null) throw new IOException("Board file is empty: " + normalized);
            document.normalize();
            return document;
        }
    }

    public void save(String name, CircuitDocument document) throws IOException {
        String normalized = validateName(name);
        if (document == null) throw new IllegalArgumentException("Board document is required");
        Files.createDirectories(boardDirectory);

        CircuitDocument copy = copyDocument(document);
        Path target = file(normalized);
        Path temporary = boardDirectory.resolve(normalized + ".json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(copy, writer);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void rename(String oldName, String newName) throws IOException {
        String oldNormalized = validateName(oldName);
        String newNormalized = validateName(newName);
        Path oldPath = file(oldNormalized);
        Path newPath = file(newNormalized);
        if (!Files.isRegularFile(oldPath)) throw new IOException("Board does not exist: " + oldNormalized);
        if (!oldPath.equals(newPath) && Files.exists(newPath)) {
            throw new IllegalArgumentException("Board already exists: " + newNormalized);
        }
        Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
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
        return boardDirectory.resolve(name + ".json");
    }

    private static String validateName(String name) {
        if (name == null) throw new IllegalArgumentException("Board name is required");
        String normalized = name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Board name is required");
        if (normalized.length() > 48) throw new IllegalArgumentException("Board name is too long (max 48)");
        if (normalized.equals(".") || normalized.equals("..")) throw new IllegalArgumentException("Invalid board name");
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0 || normalized.indexOf(':') >= 0
                || normalized.indexOf('*') >= 0 || normalized.indexOf('?') >= 0 || normalized.indexOf('"') >= 0
                || normalized.indexOf('<') >= 0 || normalized.indexOf('>') >= 0 || normalized.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Board name contains invalid file-name characters");
        }
        return normalized;
    }
}
