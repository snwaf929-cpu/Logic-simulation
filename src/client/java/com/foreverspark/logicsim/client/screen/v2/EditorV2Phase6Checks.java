package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Dependency-light regression checks for Phase 6 layout/locking behavior. */
public final class EditorV2Phase6Checks {
    private EditorV2Phase6Checks() {}

    public static void run() {
        alignmentChecks();
        distributionChecks();
        pinRowChecks();
        lockSnapshotChecks();
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

    private static EditorNode node(int id, double x, double y) {
        return new EditorNode(id, NodeKind.NAND, x, y);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
