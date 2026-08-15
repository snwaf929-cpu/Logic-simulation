package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.RoutePoint;

/** Dependency-light regression checks run as part of clientSelfTest. */
public final class EditorV2FoundationChecks {
    private EditorV2FoundationChecks() {}

    public static void run() {
        gridChecks();
        pinGeometryChecks();
        snapshotChecks();
        historyChecks();
    }

    private static void gridChecks() {
        check(EditorGrid.snap(13.0) == 12.0, "grid rounds to six-unit lattice");
        check(EditorGrid.snapUp(13.0) == 18.0, "grid snapUp uses six-unit lattice");
        check(EditorGrid.duplicateGap() == 6.0, "duplicate gap is exactly one editor grid step");
        for (double value : new double[] {-42, -6, 0, 6, 72, 138}) {
            check(EditorGrid.aligned(value), "known grid value remains aligned: " + value);
        }
    }

    private static void pinGeometryChecks() {
        check(EditorPinGeometry.halfSize(1) == 3, "one-bit signal terminal stays compact");
        check(EditorPinGeometry.halfSize(8) == 5, "8-bit bus is visibly heavier than a signal");
        check(EditorPinGeometry.halfSize(64) == 6, "64-bit bus is heavier without becoming oversized");
        check(EditorPinGeometry.contains(3, 3, 1), "visible square signal corner is clickable");
        check(!EditorPinGeometry.contains(4, 0, 1), "outside signal square is not clickable");
        check(EditorPinGeometry.contains(0, 0, 16), "bus center is clickable");
        check(!EditorPinGeometry.contains(5, 5, 16), "clipped bus corner is not clickable");
        check(!EditorPinGeometry.contains(7, 0, 64), "outside widest bus connector is not clickable");
    }

    private static void snapshotChecks() {
        CircuitDocument original = new CircuitDocument();
        EditorNode a = original.addNode(NodeKind.INPUT, 12, 18);
        a.label = "A";
        a.width = 16;
        a.inputDefaultValue = 0xA55AL;
        EditorNode b = original.addNode(NodeKind.OUTPUT, 96, 18);
        b.label = "B";
        b.width = 16;
        original.connect(a.id, 0, b.id, 0);
        original.wires.getFirst().routePoints().add(new RoutePoint(48, 18));
        original.wires.getFirst().setBranchStart(new RoutePoint(60, 18));

        CircuitDocument copy = EditorDocumentSnapshot.copy(original);
        check(EditorDocumentSnapshot.same(original, copy), "snapshot preserves the complete editor document");
        copy.node(a.id).x += 6;
        copy.wires.getFirst().routePoints().set(0, new RoutePoint(54, 18));
        check(original.node(a.id).x == 12, "snapshot nodes are deep copied");
        check(original.wires.getFirst().routePoints().getFirst().x() == 48, "snapshot routes are deep copied");
    }

    private static void historyChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        EditorHistory history = new EditorHistory(16);

        history.checkpoint("Move", document);
        input.y = 18;
        check(history.commit(document), "changed transaction commits");
        EditorHistory.Result undo = history.undo(document);
        check(undo != null && undo.document().node(input.id).y == 0, "undo restores pre-edit document");

        CircuitDocument undone = undo.document();
        EditorHistory.Result redo = history.redo(undone);
        check(redo != null && redo.document().node(input.id).y == 18, "redo restores post-edit document");

        CircuitDocument redone = redo.document();
        history.checkpoint("Selection-only no-op", redone);
        check(!history.commit(redone), "no-op transaction does not create history");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
