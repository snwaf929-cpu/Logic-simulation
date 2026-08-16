package com.foreverspark.logicsim.client.chip;

import com.foreverspark.logicsim.client.board.ClientBoardTemplateLibrary;
import com.foreverspark.logicsim.client.screen.v2.EditorV2FoundationChecks;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireLayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
            runBoardTemplatePersistenceChecks(root.resolve("board-templates"));
            EditorV2FoundationChecks.run();
            System.out.println("Client chip/BOARD-template library + Editor V2 foundation self-test: PASS");
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

    private static void runBoardTemplatePersistenceChecks(Path root) throws Exception {
        ClientBoardTemplateLibrary first = new ClientBoardTemplateLibrary(root);
        CircuitDocument board = new CircuitDocument();

        EditorNode inputSocket = board.addNode(NodeKind.BUS, 12, 18);
        inputSocket.width = 16;
        inputSocket.configureBoardSocket("DATA_IN", PortDirection.INPUT, 0);
        inputSocket.interfaceId = "backplane-data-in";

        EditorNode outputSocket = board.addNode(NodeKind.BUS, 156, 18);
        outputSocket.width = 16;
        outputSocket.configureBoardSocket("DATA_OUT", PortDirection.OUTPUT, 1);
        outputSocket.interfaceId = "backplane-data-out";
        board.connect(inputSocket.id, 0, outputSocket.id, 0);
        board.wires.getFirst().setRoutePoints(List.of(new RoutePoint(60, 18), new RoutePoint(108, 18)));
        board.wires.getFirst().setLayer(WireLayer.BACK);
        board.wires.getFirst().setViaRouteIndices(List.of(0));

        // Simulate an already-inserted nested template socket. Saving this BOARD as a new template
        // must flatten the nested module and must NOT accidentally export its socket as another boundary.
        EditorNode nestedSocket = board.addNode(NodeKind.BUS, 84, 90);
        nestedSocket.width = 4;
        nestedSocket.configureBoardSocket("NESTED", PortDirection.INPUT, 0);
        nestedSocket.interfaceId = "nested-interface";
        nestedSocket.templateInstanceId = 7;
        nestedSocket.templateName = "CHILD_TEMPLATE";

        first.save("BACKPLANE", board);
        check(first.exists("BACKPLANE"), "BOARD template file is created");

        ClientBoardTemplateLibrary reopened = new ClientBoardTemplateLibrary(root);
        check(reopened.names().equals(List.of("BACKPLANE")), "BOARD template list survives fresh reopen");
        var definition = reopened.load("BACKPLANE");
        check(definition.name.equals("BACKPLANE"), "BOARD template name survives persistence");
        check(definition.sockets().size() == 2, "nested template sockets are internalized when saving a parent template");
        check(definition.sockets().get(0).interfaceId().equals("backplane-data-in")
                        && definition.sockets().get(0).direction() == PortDirection.INPUT
                        && definition.sockets().get(0).width() == 16,
                "socket identity/direction/width survive template reopen");
        check(definition.sockets().get(1).interfaceId().equals("backplane-data-out")
                        && definition.sockets().get(1).order() == 1,
                "explicit socket ordering survives template reopen");
        check(definition.circuit.wires.size() == 1, "template internal wiring survives reopen");
        check(definition.circuit.wires.getFirst().layer() == WireLayer.BACK,
                "template PCB copper metadata survives reopen");
        check(definition.circuit.wires.getFirst().viaRouteIndices().equals(List.of(0)),
                "template PCB via metadata survives reopen");
        check(definition.circuit.formatVersion == 3,
                "V3 explicit reusable-CHIP port ordering migrates BOARD documents without breaking Phase 4 optional metadata");
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
