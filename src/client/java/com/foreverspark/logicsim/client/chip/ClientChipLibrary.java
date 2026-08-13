package com.foreverspark.logicsim.client.chip;

import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
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
 * Circuit files stay flat on disk for backwards compatibility. Folder/color organisation
 * is persisted both in library.json and inside each chip definition so a stale/missing
 * library index cannot silently turn a chip gray or forget its folder assignment.
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
        save(name, circuit, DEFAULT_CHIP_COLOR, new ChipVisualSettings(), null);
    }

    public void save(String name, CircuitDocument circuit, int color, ChipVisualSettings visual) throws IOException {
        save(name, circuit, color, visual, null);
    }

    /**
     * Saves a reusable chip while preserving its current folder unless a target folder is supplied.
     * For a brand-new chip, null means OTHER/unfiled.
     */
    public void save(String name, CircuitDocument circuit, int color, ChipVisualSettings visual, String folderName) throws IOException {
        String normalized = validateName(name);
        Files.createDirectories(chipDirectory);

        String existingCanonical = canonicalChipName(normalized);
        String targetFolder;
        if (folderName == null) {
            targetFolder = existingCanonical == null ? "" : folderOf(existingCanonical);
        } else {
            targetFolder = canonicalFolderOrOther(folderName);
        }

        ChipVisualSettings visualCopy = copyVisual(visual);
        ChipDefinition definition = new ChipDefinition(normalized, copyDocument(circuit), visualCopy);
        definition.color = forceOpaque(color);
        definition.folder = targetFolder;
        writeDefinition(definition);

        cachedDefinitions.put(normalized, copyDefinition(definition));
        ChipMeta meta = layout.chips.computeIfAbsent(normalized, ignored -> new ChipMeta());
        meta.color = definition.color;
        meta.folder = targetFolder;
        saveLayout();
    }

    public ChipDefinition load(String name) throws IOException {
        String normalized = validateName(name);
        ChipDefinition cached = findCached(normalized);
        if (cached == null) {
            refreshChipCache();
            cached = findCached(normalized);
        }
        if (cached == null) {
            throw new IOException("Chip does not exist: " + normalized);
        }
        return copyDefinition(cached);
    }

    /** Read-only lookup used by the compiler/editor. Returned definitions must not be mutated. */
    @Override
    public ChipDefinition find(String name) {
        if (name == null) return null;
        return findCached(name.trim());
    }

    public boolean exists(String name) {
        if (name == null || name.isBlank()) return false;
        return findCached(name.trim()) != null;
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
        ChipMeta meta = chipMeta(chipName);
        if (meta == null || meta.folder == null) {
            return "";
        }
        return meta.folder;
    }

    public int chipColor(String chipName) {
        ChipMeta meta = chipMeta(chipName);
        if (meta != null) {
            return normalizeColor(meta.color, DEFAULT_CHIP_COLOR);
        }
        ChipDefinition definition = findCached(chipName);
        return definition == null ? DEFAULT_CHIP_COLOR : normalizeColor(definition.color, DEFAULT_CHIP_COLOR);
    }

    public ChipVisualSettings chipVisual(String chipName) {
        ChipDefinition definition = findCached(chipName);
        return definition == null ? new ChipVisualSettings() : copyVisual(definition.visual);
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
        createFolder(name, DEFAULT_FOLDER_COLOR);
    }

    public void createFolder(String name, int color) throws IOException {
        String normalized = validateFolderName(name);
        if (folderMeta(normalized) != null) {
            throw new IllegalArgumentException("Folder already exists: " + normalized);
        }
        FolderMeta folder = new FolderMeta();
        folder.name = normalized;
        folder.color = forceOpaque(color);
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
        for (Map.Entry<String, ChipMeta> entry : layout.chips.entrySet()) {
            ChipMeta chip = entry.getValue();
            if (previous.equals(chip.folder)) {
                chip.folder = normalized;
                syncDefinitionPresentation(entry.getKey(), chip);
            }
        }
        saveLayout();
    }

    public void deleteFolder(String name) throws IOException {
        FolderMeta folder = requireFolder(name);
        layout.folders.remove(folder);
        for (Map.Entry<String, ChipMeta> entry : layout.chips.entrySet()) {
            ChipMeta chip = entry.getValue();
            if (name.equals(chip.folder)) {
                chip.folder = "";
                syncDefinitionPresentation(entry.getKey(), chip);
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
        String canonical = requireChip(chipName);
        ChipMeta meta = layout.chips.computeIfAbsent(canonical, ignored -> new ChipMeta());
        meta.color = forceOpaque(color);
        syncDefinitionPresentation(canonical, meta);
        saveLayout();
    }

    public void moveChipToFolder(String chipName, String folderName) throws IOException {
        String canonical = requireChip(chipName);
        String target = canonicalFolderOrOther(folderName);
        ChipMeta meta = layout.chips.computeIfAbsent(canonical, ignored -> new ChipMeta());
        meta.folder = target;
        syncDefinitionPresentation(canonical, meta);
        saveLayout();
    }

    /**
     * Rename a saved chip and update all saved custom-chip references to the new name.
     * This keeps existing higher-level chips valid after an F2 rename.
     */
    public void renameChip(String oldName, String newName) throws IOException {
        String oldCanonical = requireChip(oldName);
        String normalizedNew = validateName(newName);
        String existingNew = canonicalChipName(normalizedNew);
        if (existingNew != null && !existingNew.equals(oldCanonical)) {
            throw new IllegalArgumentException("Chip already exists: " + normalizedNew);
        }
        if (oldCanonical.equals(normalizedNew)) {
            return;
        }

        ChipDefinition renamed = cachedDefinitions.remove(oldCanonical);
        Path oldPath = file(oldCanonical);
        Path newPath = file(normalizedNew);
        if (!oldPath.equals(newPath) && Files.exists(newPath)) {
            cachedDefinitions.put(oldCanonical, renamed);
            throw new IllegalArgumentException("A chip file already uses that name");
        }

        ChipMeta meta = layout.chips.remove(oldCanonical);
        if (meta == null) meta = new ChipMeta();
        meta.color = normalizeColor(meta.color, renamed.color == 0 ? DEFAULT_CHIP_COLOR : renamed.color);
        if (meta.folder == null) meta.folder = renamed.folder == null ? "" : renamed.folder;

        renamed.name = normalizedNew;
        renamed.color = meta.color;
        renamed.folder = canonicalFolderOrOther(meta.folder);
        rewriteCustomChipReferences(renamed.circuit, oldCanonical, normalizedNew);
        renamed.normalize();
        writeDefinition(renamed);
        if (!oldPath.equals(newPath)) {
            Files.deleteIfExists(oldPath);
        }
        cachedDefinitions.put(normalizedNew, renamed);
        layout.chips.put(normalizedNew, meta);

        // Keep every previously saved parent chip valid after the rename.
        for (ChipDefinition definition : cachedDefinitions.values()) {
            if (definition == renamed) continue;
            if (rewriteCustomChipReferences(definition.circuit, oldCanonical, normalizedNew)) {
                writeDefinition(definition);
            }
        }

        saveLayout();
    }

    public CircuitDocument copyDocument(CircuitDocument circuit) {
        CircuitDocument copy = GSON.fromJson(GSON.toJson(circuit), CircuitDocument.class);
        copy.normalize();
        return copy;
    }

    private ChipDefinition copyDefinition(ChipDefinition definition) {
        ChipDefinition copy = GSON.fromJson(GSON.toJson(definition), ChipDefinition.class);
        copy.normalize();
        return copy;
    }

    private static ChipVisualSettings copyVisual(ChipVisualSettings visual) {
        ChipVisualSettings source = visual == null ? new ChipVisualSettings() : visual;
        source.normalize();
        return new ChipVisualSettings(source.width, source.minHeight, source.portSpacing);
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
                            definition.normalize();
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

        layout.formatVersion = Math.max(layout.formatVersion, 3);
        layout.folders.removeIf(folder -> folder == null || folder.name == null || folder.name.isBlank());
        for (FolderMeta folder : layout.folders) {
            folder.color = normalizeColor(folder.color, DEFAULT_FOLDER_COLOR);
        }

        for (ChipDefinition definition : cachedDefinitions.values()) {
            definition.normalize();
            ChipMeta meta = layout.chips.get(definition.name);
            if (meta == null) {
                meta = new ChipMeta();
                meta.color = normalizeColor(definition.color, DEFAULT_CHIP_COLOR);
                meta.folder = validDefinitionFolder(definition.folder);
                layout.chips.put(definition.name, meta);
            } else {
                int definitionFallback = definition.color == 0 ? DEFAULT_CHIP_COLOR : definition.color;
                meta.color = normalizeColor(meta.color, definitionFallback);
                if (meta.folder == null) {
                    meta.folder = validDefinitionFolder(definition.folder);
                }
                if (!meta.folder.isBlank() && folderMeta(meta.folder) == null) {
                    String recovered = validDefinitionFolder(definition.folder);
                    meta.folder = recovered;
                }
            }

            definition.color = meta.color;
            definition.folder = meta.folder == null ? "" : meta.folder;
        }
        layout.chips.keySet().removeIf(name -> canonicalChipName(name) == null);
    }

    private String validDefinitionFolder(String folderName) {
        if (folderName == null || folderName.isBlank()) return "";
        FolderMeta folder = folderMeta(folderName);
        return folder == null ? "" : folder.name;
    }

    private String canonicalFolderOrOther(String folderName) {
        if (folderName == null || folderName.isBlank()) return "";
        return requireFolder(folderName).name;
    }

    private void syncDefinitionPresentation(String chipName, ChipMeta meta) throws IOException {
        ChipDefinition definition = findCached(chipName);
        if (definition == null) return;
        definition.color = normalizeColor(meta.color, DEFAULT_CHIP_COLOR);
        definition.folder = meta.folder == null ? "" : meta.folder;
        writeDefinition(definition);
    }

    private void saveLayout() throws IOException {
        normalizeLayout();
        Files.createDirectories(rootDirectory);
        try (Writer writer = Files.newBufferedWriter(layoutFile, StandardCharsets.UTF_8)) {
            GSON.toJson(layout, writer);
        }
    }

    private void writeDefinition(ChipDefinition definition) throws IOException {
        definition.normalize();
        Files.createDirectories(chipDirectory);
        try (Writer writer = Files.newBufferedWriter(file(definition.name), StandardCharsets.UTF_8)) {
            GSON.toJson(definition, writer);
        }
    }

    private static boolean rewriteCustomChipReferences(CircuitDocument circuit, String oldName, String newName) {
        if (circuit == null || circuit.nodes == null) return false;
        boolean changed = false;
        for (EditorNode node : circuit.nodes) {
            if (node.kind == NodeKind.CUSTOM_CHIP && oldName.equals(node.chipName)) {
                node.chipName = newName;
                changed = true;
            }
        }
        return changed;
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

    private ChipMeta chipMeta(String name) {
        String canonical = canonicalChipName(name);
        return canonical == null ? null : layout.chips.get(canonical);
    }

    private ChipDefinition findCached(String name) {
        String canonical = canonicalChipName(name);
        return canonical == null ? null : cachedDefinitions.get(canonical);
    }

    private String canonicalChipName(String name) {
        if (name == null) return null;
        for (String candidate : cachedDefinitions.keySet()) {
            if (candidate.equalsIgnoreCase(name)) return candidate;
        }
        return null;
    }

    private String requireChip(String name) {
        String canonical = canonicalChipName(name);
        if (canonical == null) {
            throw new IllegalArgumentException("Chip does not exist: " + name);
        }
        return canonical;
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
        if (color == 0) {
            return forceOpaque(fallback);
        }
        return forceOpaque(color);
    }

    private static int forceOpaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    public record FolderInfo(String name, int color, boolean expanded) {
    }

    private static final class LibraryLayout {
        int formatVersion = 3;
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
        int color = 0;
    }
}
