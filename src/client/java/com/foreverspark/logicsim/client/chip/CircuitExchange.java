package com.foreverspark.logicsim.client.chip;

import com.foreverspark.logicsim.client.device.BuiltinDevices;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Portable, human-readable circuit exchange for moving reusable chips between installs
 * and for exchanging circuits with external tools/AI without touching the private library index.
 *
 * New bundles use *.logicbundle.json and contain the selected root chip plus every non-built-in
 * custom-chip dependency. Existing single-chip *.logicchip.json files are also accepted by import.
 */
public final class CircuitExchange {
    public static final String FORMAT = "logic-simulation-exchange";
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("logic-simulation").resolve("exchange");
    private static final Path IMPORT_DIR = ROOT.resolve("import");
    private static final Path EXPORT_DIR = ROOT.resolve("export");
    private static final Path PROCESSED_DIR = ROOT.resolve("processed");
    private static final Path README = ROOT.resolve("README.txt");

    private CircuitExchange() {}

    public static Path importDirectory() {
        return IMPORT_DIR;
    }

    public static Path exportDirectory() {
        return EXPORT_DIR;
    }

    public static Path suggestedExportPath(String chipName) {
        String name = chipName == null || chipName.isBlank() ? "circuit" : chipName.trim();
        return EXPORT_DIR.resolve(safeFileName(name) + ".logicbundle.json");
    }

    public static void ensureDirectories() throws IOException {
        Files.createDirectories(IMPORT_DIR);
        Files.createDirectories(EXPORT_DIR);
        Files.createDirectories(PROCESSED_DIR);
        if (!Files.exists(README)) {
            Files.writeString(README,
                    "Logic Simulation circuit exchange\n\n"
                            + "NATIVE FILE PICKER:\n"
                            + "  IMPORT FILE opens a normal system Open dialog. EXPORT SELECTED opens\n"
                            + "  a normal system Save dialog. The folders below remain as a fallback.\n\n"
                            + "IMPORT FALLBACK:\n"
                            + "  Put .logicbundle.json or .logicchip.json files in the import folder,\n"
                            + "  then use the inbox fallback. Successfully imported inbox files are\n"
                            + "  moved to processed.\n\n"
                            + "EXPORT FALLBACK:\n"
                            + "  Fixed-folder export writes a readable .logicbundle.json containing the\n"
                            + "  selected chip and all custom chip dependencies into the export folder.\n\n"
                            + "The JSON is intentionally portable and readable by humans and tools.\n",
                    StandardCharsets.UTF_8);
        }
    }

    public static ExportResult exportChip(ClientChipLibrary library, String chipName) throws IOException {
        return exportChipTo(library, chipName, suggestedExportPath(chipName));
    }

    /** Write a selected CHIP bundle to an explicit path chosen by the user/system file picker. */
    public static ExportResult exportChipTo(ClientChipLibrary library, String chipName, Path requestedTarget) throws IOException {
        if (library == null) throw new IllegalArgumentException("Chip library is required");
        if (chipName == null || chipName.isBlank()) throw new IllegalArgumentException("Select a saved chip first");
        if (requestedTarget == null) throw new IllegalArgumentException("Export path is required");
        ensureDirectories();

        LinkedHashMap<String, ChipDefinition> collected = new LinkedHashMap<>();
        collect(library, chipName.trim(), collected);
        ChipDefinition root = findIgnoreCase(collected, chipName.trim());
        if (root == null) throw new IOException("Cannot export missing chip: " + chipName);

        ExchangeBundle bundle = new ExchangeBundle();
        bundle.format = FORMAT;
        bundle.version = FORMAT_VERSION;
        bundle.root = root.name;
        bundle.chips = new ArrayList<>(collected.values());

        Path target = normalizeExportTarget(requestedTarget);
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            GSON.toJson(bundle, writer);
        }
        return new ExportResult(target, bundle.root, bundle.chips.size());
    }

    /** Import one or more explicit files selected by a native Open dialog. Selected originals are never moved. */
    public static ImportResult importFiles(ClientChipLibrary library, List<Path> selectedFiles) throws IOException {
        if (library == null) throw new IllegalArgumentException("Chip library is required");
        ensureDirectories();

        int filesImported = 0;
        int chipsImported = 0;
        List<String> roots = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> files = selectedFiles == null ? List.of() : selectedFiles;

        for (Path file : files) {
            if (file == null) continue;
            if (!Files.isRegularFile(file) || !isImportFile(file)) {
                failures.add(file.getFileName() + ": expected .logicbundle.json or .logicchip.json");
                continue;
            }
            try {
                ParsedImport parsed = readImport(file);
                int written = install(library, parsed.chips());
                filesImported++;
                chipsImported += written;
                if (parsed.root() != null && !parsed.root().isBlank()) roots.add(parsed.root());
            } catch (Exception exception) {
                failures.add(file.getFileName() + ": " + message(exception));
            }
        }
        library.reload();
        Path source = files.stream().filter(path -> path != null).findFirst()
                .map(Path::toAbsolutePath).map(Path::getParent).orElse(IMPORT_DIR);
        return new ImportResult(filesImported, chipsImported, List.copyOf(roots), List.copyOf(failures), source);
    }

    /** Legacy/fallback inbox import retained for headless systems and direct folder workflows. */
    public static ImportResult importInbox(ClientChipLibrary library) throws IOException {
        if (library == null) throw new IllegalArgumentException("Chip library is required");
        ensureDirectories();

        int filesImported = 0;
        int chipsImported = 0;
        List<String> roots = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(IMPORT_DIR, path -> Files.isRegularFile(path) && isImportFile(path))) {
            for (Path file : stream) {
                try {
                    ParsedImport parsed = readImport(file);
                    int written = install(library, parsed.chips());
                    filesImported++;
                    chipsImported += written;
                    if (parsed.root() != null && !parsed.root().isBlank()) roots.add(parsed.root());
                    moveProcessed(file);
                } catch (Exception exception) {
                    failures.add(file.getFileName() + ": " + message(exception));
                }
            }
        }
        library.reload();
        return new ImportResult(filesImported, chipsImported, List.copyOf(roots), List.copyOf(failures), IMPORT_DIR);
    }

    private static void collect(ClientChipLibrary library, String name, LinkedHashMap<String, ChipDefinition> collected) throws IOException {
        if (BuiltinDevices.isBuiltin(name)) return;
        String key = name.toLowerCase(Locale.ROOT);
        if (collected.containsKey(key)) return;

        ChipDefinition definition = library.load(name);
        definition.normalize();
        collected.put(key, definition);

        if (definition.circuit == null || definition.circuit.nodes == null) return;
        for (EditorNode node : definition.circuit.nodes) {
            if (node != null && node.kind == NodeKind.CUSTOM_CHIP && node.chipName != null && !node.chipName.isBlank()) {
                collect(library, node.chipName.trim(), collected);
            }
        }
    }

    private static ParsedImport readImport(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            normalizeUnsignedArgbColors(json);

            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".logicchip.json")) {
                ChipDefinition definition = GSON.fromJson(json, ChipDefinition.class);
                validateDefinition(definition);
                return new ParsedImport(definition.name, List.of(definition));
            }

            ExchangeBundle bundle = GSON.fromJson(json, ExchangeBundle.class);
            if (bundle == null || !FORMAT.equals(bundle.format)) {
                throw new IOException("Not a " + FORMAT + " bundle");
            }
            if (bundle.version < 1 || bundle.version > FORMAT_VERSION) {
                throw new IOException("Unsupported exchange version " + bundle.version);
            }
            if (bundle.chips == null || bundle.chips.isEmpty()) throw new IOException("Bundle contains no chips");
            for (ChipDefinition definition : bundle.chips) validateDefinition(definition);
            String root = bundle.root == null || bundle.root.isBlank() ? bundle.chips.get(0).name : bundle.root.trim();
            return new ParsedImport(root, List.copyOf(bundle.chips));
        }
    }

    /**
     * ARGB colors are Java signed ints internally, but external tools commonly serialize them as
     * the equivalent unsigned 32-bit decimal value (for example 0xFF5FA8FF = 4284459263).
     * Accept both representations so portable/AI-generated bundles do not fail in Gson's int adapter.
     */
    private static void normalizeUnsignedArgbColors(JsonElement element) throws IOException {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) normalizeUnsignedArgbColors(child);
            return;
        }
        if (!element.isJsonObject()) return;

        JsonObject object = element.getAsJsonObject();
        JsonElement color = object.get("color");
        if (color != null && color.isJsonPrimitive() && color.getAsJsonPrimitive().isNumber()) {
            long value;
            try {
                value = color.getAsLong();
            } catch (RuntimeException exception) {
                throw new IOException("Invalid ARGB color value", exception);
            }
            if (value > Integer.MAX_VALUE) {
                if (value > 0xFFFF_FFFFL) throw new IOException("ARGB color exceeds 32 bits: " + value);
                object.addProperty("color", (int) value);
            } else if (value < Integer.MIN_VALUE) {
                throw new IOException("ARGB color is below signed 32-bit range: " + value);
            }
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!"color".equals(entry.getKey())) normalizeUnsignedArgbColors(entry.getValue());
        }
    }

    private static int install(ClientChipLibrary library, List<ChipDefinition> definitions) throws IOException {
        int count = 0;
        for (ChipDefinition definition : definitions) {
            validateDefinition(definition);
            if (BuiltinDevices.isBuiltin(definition.name)) {
                throw new IOException("Reserved built-in device ID cannot be imported: " + definition.name);
            }
            int color = definition.color == 0 ? ClientChipLibrary.DEFAULT_CHIP_COLOR : definition.color;
            library.save(definition.name, definition.circuit, color, definition.visual, "");
            count++;
        }
        return count;
    }

    private static void validateDefinition(ChipDefinition definition) throws IOException {
        if (definition == null) throw new IOException("Missing chip definition");
        if (definition.name == null || definition.name.isBlank()) throw new IOException("Chip has no name");
        if (definition.circuit == null) throw new IOException("Chip " + definition.name + " has no circuit");
        definition.normalize();
    }

    private static boolean isImportFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".logicbundle.json") || name.endsWith(".logicchip.json");
    }

    private static Path normalizeExportTarget(Path requestedTarget) {
        Path absolute = requestedTarget.toAbsolutePath().normalize();
        String name = absolute.getFileName() == null ? "circuit" : absolute.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".logicbundle.json")) return absolute;
        Path parent = absolute.getParent();
        String normalizedName = name + ".logicbundle.json";
        return parent == null ? Path.of(normalizedName) : parent.resolve(normalizedName);
    }

    private static void moveProcessed(Path source) throws IOException {
        Path target = PROCESSED_DIR.resolve(source.getFileName());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static ChipDefinition findIgnoreCase(Map<String, ChipDefinition> definitions, String name) {
        return definitions.get(name.toLowerCase(Locale.ROOT));
    }

    private static String safeFileName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "circuit" : safe;
    }

    private static String message(Exception exception) {
        String text = exception.getMessage();
        return text == null || text.isBlank() ? exception.getClass().getSimpleName() : text;
    }

    public record ExportResult(Path path, String root, int chipCount) {}

    public record ImportResult(int fileCount, int chipCount, List<String> roots, List<String> failures, Path importDirectory) {
        public boolean importedAnything() { return fileCount > 0; }
        public boolean hasFailures() { return failures != null && !failures.isEmpty(); }
    }

    private record ParsedImport(String root, List<ChipDefinition> chips) {}

    private static final class ExchangeBundle {
        String format = FORMAT;
        int version = FORMAT_VERSION;
        String root = "";
        List<ChipDefinition> chips = new ArrayList<>();
    }
}
