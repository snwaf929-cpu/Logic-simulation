package com.foreverspark.logicsim.client.chip;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side reusable chip library.
 *
 * Circuit files stay flat on disk so existing saves remain compatible. Folder/color
 * organisation is stored separately in library.json and only affects editor UX.
 */
public final class ClientChipLibrary implements ChipLookup {
    public static final int DEFAULT_CHIP_COLOR = 0xFF59636E;
    public static final int DEFAULT_FOLDER_COLOR = 0xFF4C86D9;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rootDirectory = FabricLoader.getInstance().getConfigDir()
            .resolve("logic-simulation");
    private final Path chipDirectory = rootDirectory.resolve("chips");
    private final Path layoutFile = rootDirectory.resolve("library.json");

    private final Map<String, ChipDefinition> cachedDefinitions = new LinkedHashMap<>();
    private LibraryLayout layout = new LibraryLayout();

    public ClientChipLibrary() {
        reload();
    }

    public void reload() {
        loadLayout();
        refreshChipCache();
        normalizeLayout();
    }

    public void save(String name, CircuitDocument circuit) throws IOException {
        String normalized = validateName(name);
        Files.createDirectories(chipDirectory);

        ChipDefinition definition = new ChipDefinition(normalized, copyDocument(circuit));
        try (Writer writer = Files.newBufferedWriter(file(normalized), StandardCharsets.UTF_8)) {
            GSON.toJson(definition, writer);
        }

        cachedDefinitions.put(normalized, copyDefinition(definition));
        layout.chips.computeIfAbsent(normalized, ignored -> new ChipMeta());
        saveLayout();
    }

    public ChipDefinition load(String name) throws IOException {
        String normalized = validateName(name);
        ChipDefinition cached = cachedDefinitions.get(normalized);
        if (cached == null) {
            refreshChipCache();
            cached = cachedDefinitions.get(normalized);
        }
        if (cached == null) {
            throw new IOException("Chip does not exist: " + normalized);
        }
        return copyDefinition(cached);
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
        List<String> names = new ArrayList<>(cachedDefinitions.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    public List<FolderInfo> folders() {
        List<FolderInfo> result = new ArrayList<>();
        for (FolderMeta folder : layout.folders) {
            result.add(new FolderInfo(folder.name, folder.color, folder.expanded));
        }
        return List.copyOf(result);
    }

    public List<String> chipsInFolder(String folderName) {
        String folder = folderName == null ? "" : folderName;
        List<String> result = new ArrayList<>();
        for (String chipName : names()) {
            if (folder.equals(folderOf(chipName))) {
                result.add(chipName);
            }
        }
        return List.copyOf(result);
    }

    public List<String> unfiledChips() {
        return chipsInFolder("");
    }

    public String folderOf(String chipName) {
        ChipMeta meta = layout.chips.get(chipName);
        if (meta == null || meta.folder == null) {
            return "";
        }
        return meta.folder;
    }

    public int chipColor(String chipName) {
        ChipMeta meta = layout.chips.get(chipName);
        return meta == null ? DEFAULT_CHIP_COLOR : normalizeColor(meta.color, DEFAULT_CHIP_COLOR);
    }

    public int folderColor(String folderName) {
        FolderMeta meta = folderMeta(folderName);
        return meta == null ? DEFAULT_FOLDER_COLOR : normalizeColor(meta.color, DEFAULT_FOLDER_COLOR);
    }

    public boolean folderExpanded(String folderName) {
        FolderMeta meta = folderMeta(folderName);
        return meta == null || meta.expanded;
    }

    public void createFolder(String name) throws IOException {
        String normalized = validateFolderName(name);
        if (folderMeta(normalized) != null) {
            throw new IllegalArgumentException("Folder already exists: " + normalized);
        }
        FolderMeta folder = new FolderMeta();
        folder.name = normalized;
        folder.color = DEFAULT_FOLDER_COLOR;
        folder.expanded = true;
        layout.folders.add(folder);
        saveLayout();
    }

    public void renameFolder(String oldName, String newName) throws IOException {
        FolderMeta folder = requireFolder(oldName);
        String normalized = validateFolderName(newName);
        FolderMeta collision = folderMeta(normalized);
        if (collision != null && collision != folder) {
            throw new IllegalArgumentException("Folder already exists: " + normalized);
        }
        String previous = folder.name;
        folder.name = normalized;
        for (ChipMeta chip : layout.chips.values()) {
            if (previous.equals(chip.folder)) {
                chip.folder = normalized;
            }
        }
        saveLayout();
    }

    public void deleteFolder(String name) throws IOException {
        FolderMeta folder = requireFolder(name);
        layout.folders.remove(folder);
        for (ChipMeta chip : layout.chips.values()) {
            if (name.equals(chip.folder)) {
                chip.folder = "";
            }
        }
        saveLayout();
    }

    public void setFolderExpanded(String name, boolean expanded) throws IOException {
        FolderMeta folder = requireFolder(name);
        folder.expanded = expanded;
        saveLayout();
    }

    public void setFolderColor(String name, int color) throws IOException {
        FolderMeta folder = requireFolder(name);
        folder.color = forceOpaque(color);
        saveLayout();
    }

    public void setChipColor(String chipName, int color) throws IOException {
        requireChip(chipName);
        ChipMeta meta = layout.chips.computeIfAbsent(chipName, ignored -> new ChipMeta());
        meta.color = forceOpaque(color);
        saveLayout();
    }

    public void moveChipToFolder(String chipName, String folderName) throws IOException {
        requireChip(chipName);
        String target = folderName == null ? "" : folderName;
        if (!target.isBlank()) {
            requireFolder(target);
        }
        ChipMeta meta = layout.chips.computeIfAbsent(chipName, ignored -> new ChipMeta());
        meta.folder = target;
        saveLayout();
    }

    public CircuitDocument copyDocument(CircuitDocument circuit) {
        CircuitDocument copy = GSON.fromJson(GSON.toJson(circuit), CircuitDocument.class);
        copy.normalize();
        return copy;
    }

    private ChipDefinition copyDefinition(ChipDefinition definition) {
        ChipDefinition copy = GSON.fromJson(GSON.toJson(definition), ChipDefinition.class);
        if (copy.circuit == null) {
            copy.circuit = new CircuitDocument();
        }
        copy.circuit.normalize();
        return copy;
    }

    private void refreshChipCache() {
        cachedDefinitions.clear();
        if (!Files.isDirectory(chipDirectory)) {
            return;
        }

        try (var stream = Files.list(chipDirectory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".logicchip.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                            ChipDefinition definition = GSON.fromJson(reader, ChipDefinition.class);
                            if (definition == null || definition.circuit == null || definition.name == null || definition.name.isBlank()) {
                                return;
                            }
                            definition.circuit.normalize();
                            cachedDefinitions.put(definition.name, definition);
                        } catch (Exception ignored) {
                            // A corrupt chip must not prevent the editor from opening.
                        }
                    });
        } catch (IOException ignored) {
            // Keep an empty cache if the folder cannot be read.
        }
    }

    private void loadLayout() {
        layout = new LibraryLayout();
        if (!Files.isRegularFile(layoutFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(layoutFile, StandardCharsets.UTF_8)) {
            LibraryLayout loaded = GSON.fromJson(reader, LibraryLayout.class);
            if (loaded != null) {
                layout = loaded;
            }
        } catch (Exception ignored) {
            layout = new LibraryLayout();
        }
        normalizeLayout();
    }

    private void normalizeLayout() {
        if (layout.folders == null) {
            layout.folders = new ArrayList<>();
        }
        if (layout.chips == null) {
            layout.chips = new LinkedHashMap<>();
        }

        layout.folders.removeIf(folder -> folder == null || folder.name == null || folder.name.isBlank());
        for (FolderMeta folder : layout.folders) {
            folder.color = normalizeColor(folder.color, DEFAULT_FOLDER_COLOR);
        }

        for (String chipName : cachedDefinitions.keySet()) {
            ChipMeta meta = layout.chips.computeIfAbsent(chipName, ignored -> new ChipMeta());
            meta.color = normalizeColor(meta.color, DEFAULT_CHIP_COLOR);
            if (meta.folder == null || (!meta.folder.isBlank() && folderMeta(meta.folder) == null)) {
                meta.folder = "";
            }
        }
    }

    private void saveLayout() throws IOException {
        normalizeLayout();
        Files.createDirectories(rootDirectory);
        try (Writer writer = Files.newBufferedWriter(layoutFile, StandardCharsets.UTF_8)) {
            GSON.toJson(layout, writer);
        }
    }

    private FolderMeta folderMeta(String name) {
        if (name == null) {
            return null;
        }
        for (FolderMeta folder : layout.folders) {
            if (folder.name.equalsIgnoreCase(name)) {
                return folder;
            }
        }
        return null;
    }

    private FolderMeta requireFolder(String name) {
        FolderMeta folder = folderMeta(name);
        if (folder == null) {
            throw new IllegalArgumentException("Folder does not exist: " + name);
        }
        return folder;
    }

    private void requireChip(String name) {
        if (!cachedDefinitions.containsKey(name)) {
            throw new IllegalArgumentException("Chip does not exist: " + name);
        }
    }

    private Path file(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return chipDirectory.resolve(safe + ".logicchip.json");
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

    private static String validateFolderName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Folder name is required");
        }
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Folder name is required");
        }
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("Folder name is too long (max 32)");
        }
        return normalized;
    }

    private static int normalizeColor(int color, int fallback) {
        if ((color & 0x00FFFFFF) == 0) {
            return fallback;
        }
        return forceOpaque(color);
    }

    private static int forceOpaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    public record FolderInfo(String name, int color, boolean expanded) {
    }

    private static final class LibraryLayout {
        int formatVersion = 1;
        List<FolderMeta> folders = new ArrayList<>();
        Map<String, ChipMeta> chips = new LinkedHashMap<>();
    }

    private static final class FolderMeta {
        String name = "";
        int color = DEFAULT_FOLDER_COLOR;
        boolean expanded = true;
    }

    private static final class ChipMeta {
        String folder = "";
        int color = DEFAULT_CHIP_COLOR;
    }
}
