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

/** Small, non-electrical editor preference store. Favorites never alter chip definitions or circuit behavior. */
public final class ClientEditorPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rootDirectory;
    private final Path file;
    private final LinkedHashSet<String> favorites = new LinkedHashSet<>();

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
        if (!favorites.remove(oldNormalized)) return;
        favorites.add(newNormalized);
        save();
    }

    private void load() {
        favorites.clear();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Layout layout = GSON.fromJson(reader, Layout.class);
            if (layout == null || layout.favorites == null) return;
            for (String key : layout.favorites) {
                String normalized = normalizeKey(key);
                if (!normalized.isEmpty()) favorites.add(normalized);
            }
        } catch (Exception ignored) {
            favorites.clear();
        }
    }

    private void save() throws IOException {
        Files.createDirectories(rootDirectory);
        Layout layout = new Layout();
        layout.favorites.addAll(favorites);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(layout, writer);
        }
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Layout {
        int formatVersion = 1;
        List<String> favorites = new ArrayList<>();
    }
}
