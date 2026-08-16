package com.foreverspark.logicsim.client.screen.v2;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Small, non-electrical editor preference store. Favorites/recents never alter chip definitions or circuit behavior. */
public final class ClientEditorPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_RECENT_CHIPS = 8;
    private static final int MAX_RECENT_COMPONENTS = 8;

    private final Path rootDirectory;
    private final Path file;
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();
    /** Newest-first stable chip keys ("chip:name"). */
    private final LinkedHashSet<String> recentChips = new LinkedHashSet<>();
    /** Newest-first stable built-in component IDs (INPUT, NAND, CLOCK, BUS_SLICE, ...). */
    private final LinkedHashSet<String> recentComponents = new LinkedHashSet<>();

    public ClientEditorPreferences() {
        this(FabricLoader.getInstance().getConfigDir().resolve("logic-simulation"));
    }

    ClientEditorPreferences(Path rootDirectory) {
        if (rootDirectory == null) throw new IllegalArgumentException("Preference root is required");
        this.rootDirectory = rootDirectory;
        this.file = rootDirectory.resolve("editor-preferences.json");
        load();
    }

    public boolean isFavorite(String key) {
        String normalized = normalizeKey(key);
        return !normalized.isEmpty() && favorites.contains(normalized);
    }

    public List<String> favorites() {
        return List.copyOf(favorites);
    }

    public List<String> recentChipNames() {
        ArrayList<String> names = new ArrayList<>(recentChips.size());
        for (String key : recentChips) {
            if (key.startsWith("chip:") && key.length() > "chip:".length()) names.add(key.substring("chip:".length()));
        }
        return List.copyOf(names);
    }

    public List<String> recentComponentIds() {
        return List.copyOf(recentComponents);
    }

    public void recordRecentChip(String chipName) throws IOException {
        String name = chipName == null ? "" : chipName.trim();
        if (name.isEmpty()) return;
        recordNewest(recentChips, "chip:" + name, MAX_RECENT_CHIPS, true);
        save();
    }

    public void recordRecentComponent(String componentId) throws IOException {
        String id = componentId == null ? "" : componentId.trim().toUpperCase(Locale.ROOT);
        if (id.isEmpty()) return;
        recordNewest(recentComponents, id, MAX_RECENT_COMPONENTS, false);
        save();
    }

    public boolean toggleFavorite(String key) throws IOException {
        String normalized = normalizeKey(key);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Favorite key is required");
        boolean nowFavorite;
        if (favorites.remove(normalized)) nowFavorite = false;
        else {
            favorites.add(normalized);
            nowFavorite = true;
        }
        save();
        return nowFavorite;
    }

    public void renameFavorite(String oldKey, String newKey) throws IOException {
        String oldNormalized = normalizeKey(oldKey);
        String newNormalized = normalizeKey(newKey);
        if (oldNormalized.isEmpty() || newNormalized.isEmpty() || oldNormalized.equals(newNormalized)) return;
        boolean changed = false;
        if (favorites.remove(oldNormalized)) {
            favorites.add(newNormalized);
            changed = true;
        }
        if (oldNormalized.startsWith("chip:") && newNormalized.startsWith("chip:")) {
            LinkedHashSet<String> renamed = new LinkedHashSet<>();
            for (String recent : recentChips) {
                if (recent.equalsIgnoreCase(oldNormalized)) {
                    renamed.add(newNormalized);
                    changed = true;
                } else renamed.add(recent);
            }
            recentChips.clear();
            recentChips.addAll(renamed);
        }
        if (changed) save();
    }

    private void load() {
        favorites.clear();
        recentChips.clear();
        recentComponents.clear();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Layout layout = GSON.fromJson(reader, Layout.class);
            if (layout == null) return;
            if (layout.favorites != null) {
                for (String key : layout.favorites) {
                    String normalized = normalizeKey(key);
                    if (!normalized.isEmpty()) favorites.add(normalized);
                }
            }
            if (layout.recentChips != null) {
                for (String key : layout.recentChips) {
                    String normalized = normalizeKey(key);
                    if (!normalized.startsWith("chip:") || normalized.length() <= 5) continue;
                    recentChips.add(normalized);
                    if (recentChips.size() >= MAX_RECENT_CHIPS) break;
                }
            }
            if (layout.recentComponents != null) {
                for (String id : layout.recentComponents) {
                    String normalized = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
                    if (normalized.isEmpty()) continue;
                    recentComponents.add(normalized);
                    if (recentComponents.size() >= MAX_RECENT_COMPONENTS) break;
                }
            }
        } catch (Exception ignored) {
            favorites.clear();
            recentChips.clear();
            recentComponents.clear();
        }
    }

    private void save() throws IOException {
        Files.createDirectories(rootDirectory);
        Layout layout = new Layout();
        layout.favorites.addAll(favorites);
        layout.recentChips.addAll(recentChips);
        layout.recentComponents.addAll(recentComponents);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(layout, writer);
        }
    }

    private static void recordNewest(LinkedHashSet<String> target, String value, int max, boolean ignoreCase) {
        LinkedHashSet<String> reordered = new LinkedHashSet<>();
        reordered.add(value);
        for (String existing : target) {
            boolean same = ignoreCase ? existing.equalsIgnoreCase(value) : existing.equals(value);
            if (same) continue;
            reordered.add(existing);
            if (reordered.size() >= max) break;
        }
        target.clear();
        target.addAll(reordered);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Layout {
        int formatVersion = 3;
        List<String> favorites = new ArrayList<>();
        List<String> recentChips = new ArrayList<>();
        List<String> recentComponents = new ArrayList<>();
    }
}
