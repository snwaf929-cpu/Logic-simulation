package com.foreverspark.logicsim.client.chip;

import com.foreverspark.logicsim.client.screen.v2.EditorV2FoundationChecks;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * File-system regression test for the editor library metadata path.
 * It intentionally constructs fresh library objects to catch reopen/reload ordering bugs.
 */
public final class LibraryPersistenceSelfTest {
    private LibraryPersistenceSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("logic-simulation-library-test-");
        try {
            runPersistenceChecks(root);
            EditorV2FoundationChecks.run();
            System.out.println("Client chip library + Editor V2 foundation self-test: PASS");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void runPersistenceChecks(Path root) throws Exception {
        int chipColor = 0xFFB442A8;
        int folderColor = 0xFF2E8B79;

        ClientChipLibrary first = new ClientChipLibrary(root);
        first.createFolder("Arithmetic", folderColor);
        first.save("ALU_PART", sampleCircuit(), chipColor, new ChipVisualSettings(118, 90, 22), "Arithmetic");

        ChipDefinition firstDefinition = first.load("ALU_PART");
        check(firstDefinition.color == chipColor, "chip file stores selected color");
        check(firstDefinition.folder.equals("Arithmetic"), "chip file stores folder membership");

        // The critical regression: construct a completely fresh library instance. The old bug
        // normalized before loading the chip cache and recreated this entry as gray/OTHER.
        ClientChipLibrary reopened = new ClientChipLibrary(root);
        check(reopened.chipColor("ALU_PART") == chipColor, "chip color survives fresh reopen");
        check(reopened.folderOf("ALU_PART").equals("Arithmetic"), "folder membership survives fresh reopen");
        check(reopened.folderColor("Arithmetic") == folderColor, "folder color survives fresh reopen");

        reopened.createFolder("Logic", 0xFF5A7FC2);
        reopened.moveChipToFolder("ALU_PART", "Logic");
        reopened.setChipColor("ALU_PART", 0xFF000000);

        ClientChipLibrary moved = new ClientChipLibrary(root);
        check(moved.chipColor("ALU_PART") == 0xFF000000, "true black chip color survives reopen");
        check(moved.folderOf("ALU_PART").equals("Logic"), "moved folder survives reopen");
        ChipDefinition movedDefinition = moved.load("ALU_PART");
        check(movedDefinition.color == 0xFF000000, "updated color synced into chip file");
        check(movedDefinition.folder.equals("Logic"), "updated folder synced into chip file");

        // A missing library index should still recover chip color + folder membership from the
        // redundant metadata in the .logicchip.json. Folder color falls back to the default.
        Files.deleteIfExists(root.resolve("library.json"));
        ClientChipLibrary recovered = new ClientChipLibrary(root);
        check(recovered.chipColor("ALU_PART") == 0xFF000000, "chip color recovers without library.json");
        check(recovered.folderOf("ALU_PART").equals("Logic"), "folder membership recovers without library.json");
        check(recovered.folders().stream().anyMatch(folder -> folder.name().equals("Logic")), "folder name reconstructed from chip metadata");
    }

    private static CircuitDocument sampleCircuit() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.label = "A";
        input.width = 16;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 140, 0);
        output.label = "RESULT";
        output.width = 16;
        return document;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
