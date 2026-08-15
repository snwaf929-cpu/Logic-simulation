package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Dependency-light regression checks for Phase 6 layout/locking/search/error behavior. */
public final class EditorV2Phase6Checks {
    private EditorV2Phase6Checks() {}

    public static void run() {
        alignmentChecks();
        distributionChecks();
        pinRowChecks();
        lockSnapshotChecks();
        preferenceChecks();
        errorLocatorChecks();
    }

    private static void alignmentChecks() {
        EditorNode a = node(1, 12, 18);
        EditorNode b = node(2, 96, 72);
        EditorNode c = node(3, 210, 126);
        List<EditorNode> nodes = List.of(a, b, c);
        ToDoubleFunction<EditorNode> width = node -> switch (node.id) {
            case 1 -> 60.0;
            case 2 -> 84.0;
            default -> 72.0;
        };
        ToDoubleFunction<EditorNode> height = node -> switch (node.id) {
            case 1 -> 42.0;
            case 2 -> 54.0;
            default -> 66.0;
        };

        check(EditorLayoutTools.align(nodes, EditorLayoutTools.Alignment.LEFT, width, height), "left alignment changes uneven nodes");
        check(a.x == 12 && b.x == 12 && c.x == 12, "left edges align exactly to the grid");

        check(EditorLayoutTools.align(nodes, EditorLayoutTools.Alignment.RIGHT, width, height), "right alignment changes uneven widths");
        double right = a.x + width.applyAsDouble(a);
        check(right == b.x + width.applyAsDouble(b) && right == c.x + width.applyAsDouble(c), "right edges align despite different body widths");
        check(EditorGrid.aligned(a.x) && EditorGrid.aligned(b.x) && EditorGrid.aligned(c.x), "right alignment keeps node origins grid aligned");

        check(EditorLayoutTools.align(nodes, EditorLayoutTools.Alignment.TOP, width, height), "top alignment changes uneven nodes");
        check(a.y == b.y && b.y == c.y, "top edges align");

        check(EditorLayoutTools.align(nodes, EditorLayoutTools.Alignment.BOTTOM, width, height), "bottom alignment changes uneven heights");
        double bottom = a.y + height.applyAsDouble(a);
        check(bottom == b.y + height.applyAsDouble(b) && bottom == c.y + height.applyAsDouble(c), "bottom edges align despite different body heights");
    }

    private static void distributionChecks() {
        EditorNode a = node(10, 0, 0);
        EditorNode b = node(11, 24, 30);
        EditorNode c = node(12, 120, 120);
        List<EditorNode> nodes = List.of(a, b, c);
        ToDoubleFunction<EditorNode> size = ignored -> 24.0;

        check(EditorLayoutTools.distribute(nodes, EditorLayoutTools.Axis.HORIZONTAL, size, size), "horizontal distribution changes middle component");
        check(a.x == 0 && b.x == 60 && c.x == 120, "horizontal distribution creates equal body gaps while preserving outer anchors");
        check(EditorGrid.aligned(b.x), "horizontal distribution snaps the middle component");

        check(EditorLayoutTools.distribute(nodes, EditorLayoutTools.Axis.VERTICAL, size, size), "vertical distribution changes middle component");
        check(a.y == 0 && b.y == 60 && c.y == 120, "vertical distribution creates equal body gaps while preserving outer anchors");
        check(EditorGrid.aligned(b.y), "vertical distribution snaps the middle component");
    }

    private static void pinRowChecks() {
        EditorNode a = node(20, 0, 0);
        EditorNode b = node(21, 84, 48);
        ToDoubleFunction<EditorNode> firstPin = node -> node.id == 20 ? node.y + 12.0 : node.y + 30.0;

        check(EditorLayoutTools.alignPinRows(List.of(a, b), firstPin), "pin-row alignment moves mismatched connector rows");
        check(firstPin.applyAsDouble(a) == firstPin.applyAsDouble(b), "first connector rows end on the same world Y");
        check(EditorGrid.aligned(a.y) && EditorGrid.aligned(b.y), "pin-row alignment preserves grid-aligned node origins");
    }

    private static void lockSnapshotChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 12, 18);
        input.locked = true;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 96, 18);
        document.connect(input.id, 0, output.id, 0);

        CircuitDocument copy = EditorDocumentSnapshot.copy(document);
        check(copy.node(input.id).locked, "lock state survives undo/redo deep snapshots");
        check(EditorDocumentSnapshot.same(document, copy), "lock state participates in snapshot equality");
        check(EditorLayoutTools.lockedCount(List.of(input, output)) == 1, "locked selection count is exact");

        copy.node(input.id).locked = false;
        check(!EditorDocumentSnapshot.same(document, copy), "changing only lock state creates a real editor history change");
    }

    private static void preferenceChecks() {
        Path root = null;
        try {
            root = Files.createTempDirectory("logic-simulation-phase6-preferences-");
            ClientEditorPreferences first = new ClientEditorPreferences(root);
            check(first.toggleFavorite("component:NAND"), "NAND can be pinned");
            check(first.toggleFavorite("chip:REG16"), "saved chip can be pinned");

            ClientEditorPreferences reopened = new ClientEditorPreferences(root);
            check(reopened.isFavorite("component:NAND"), "primitive favorite survives fresh reopen");
            check(reopened.isFavorite("chip:REG16"), "chip favorite survives fresh reopen");
            reopened.renameFavorite("chip:REG16", "chip:REG16_V2");

            ClientEditorPreferences renamed = new ClientEditorPreferences(root);
            check(!renamed.isFavorite("chip:REG16") && renamed.isFavorite("chip:REG16_V2"), "favorite follows chip rename");
            check(!renamed.toggleFavorite("component:NAND"), "favorite can be unpinned atomically");
        } catch (IOException exception) {
            throw new AssertionError("Phase 6 preference persistence failed", exception);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void errorLocatorChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode source = document.addNode(NodeKind.INPUT, 0, 0);
        source.width = 16;
        EditorNode target = document.addNode(NodeKind.OUTPUT, 120, 0);
        target.width = 8;
        Set<Integer> widthIds = EditorErrorLocator.locate(document,
                "Width mismatch: node " + source.id + " output is 16-bit but node " + target.id + " input is 8-bit");
        check(widthIds.equals(Set.of(source.id, target.id)), "width mismatch highlights both exact endpoint nodes");

        EditorNode netA = document.addNode(NodeKind.NET_LABEL, 0, 72);
        netA.label = "DATA_BUS";
        EditorNode netB = document.addNode(NodeKind.NET_LABEL, 120, 72);
        netB.label = "data_bus";
        Set<Integer> netIds = EditorErrorLocator.locate(document, "NET_LABEL DATA_BUS has multiple drivers");
        check(netIds.contains(netA.id) && netIds.contains(netB.id), "net conflict highlights every matching label object");

        EditorNode custom = document.addCustomChip("MISSING_ALU", 0, 144);
        Set<Integer> chipIds = EditorErrorLocator.locate(document, "Missing custom chip: MISSING_ALU");
        check(chipIds.equals(Set.of(custom.id)), "missing custom chip diagnostic highlights its authored instance");

        EditorNode loop = document.addNode(NodeKind.BUS, 0, 216);
        loop.label = "LOOP_BUS";
        Set<Integer> loopIds = EditorErrorLocator.locate(document,
                "Structural wiring loop detected at " + loop.displayName() + " output 0. BUS routing cannot feed back into itself.");
        check(loopIds.contains(loop.id), "structural loop diagnostic resolves the named schematic object");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            throw new AssertionError("Could not clean Phase 6 preference test directory", exception);
        }
    }

    private static EditorNode node(int id, double x, double y) {
        return new EditorNode(id, NodeKind.NAND, x, y);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
