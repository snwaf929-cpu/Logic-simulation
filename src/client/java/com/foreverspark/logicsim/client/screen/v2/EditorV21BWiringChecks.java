package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.List;

/** Dependency-light regression checks for the Editor V2.1B canonical wiring model. */
public final class EditorV21BWiringChecks {
    private EditorV21BWiringChecks() {}

    public static void run() {
        virtualAutorouteDoesNotMutate();
        explicitRouteIsOrthogonal();
        legacyHiddenCornersNormalize();
        legacyCornerDragKeepsClickedCorner();
        legacySegmentHandleTargetsClickedLeg();
        endpointSegmentBecomesDraggable();
        segmentMovesPerpendicular();
        viaIndicesFollowInsertedHandles();
    }

    private static void virtualAutorouteDoesNotMutate() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(60, 42);
        List<RoutePoint> visible = EditorWireRouting.visibleRoute(wire, start, end);
        check(visible.size() == 2, "diagonal autoroute exposes two editable virtual corners");
        check(wire.routePoints().isEmpty(), "selecting/viewing autoroute does not silently persist routing metadata");
        EditorWireRouting.materialize(wire, start, end);
        check(wire.routePoints().equals(visible), "first manual edit materializes exactly the route the user saw");
    }

    private static void explicitRouteIsOrthogonal() {
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(90, 48);
        List<RoutePoint> route = EditorWireRouting.explicitRoute(start, List.of(
                new EditorWireRouting.Point(25, 19),
                new EditorWireRouting.Point(67, 31)
        ), end);
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        wire.setRoutePoints(route);
        assertOrthogonal(wire, start, end, "persisted V2.1B route contains no hidden diagonal segment");
        check(!route.isEmpty(), "manual click route produces persistent route points");
        for (var point : EditorWireRouting.fullPoints(wire, start, end, false)) {
            check(EditorGrid.aligned(point.x()) && EditorGrid.aligned(point.y()), "manual route stays on editor grid");
        }
    }

    private static void legacyHiddenCornersNormalize() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(96, 54);
        // The old editor allowed this diagonal metadata and rendered it as a hidden L corner.
        wire.setRoutePoints(List.of(new RoutePoint(42, 30), new RoutePoint(72, 42)));
        wire.setViaRouteIndices(List.of(1));
        EditorWireRouting.materialize(wire, start, end);
        assertOrthogonal(wire, start, end, "first V2.1B edit expands legacy hidden corners into explicit orthogonal points");
        check(wire.viaRouteIndices().size() == 1 && wire.viaRouteIndices().getFirst() >= 1,
                "legacy PCB via remains attached after route normalization");
    }

    private static void legacyCornerDragKeepsClickedCorner() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(96, 54);
        wire.setRoutePoints(List.of(new RoutePoint(42, 30), new RoutePoint(72, 42)));

        // The click was made against old route index 1. Materialization inserts explicit L corners before the drag.
        EditorWireRouting.materialize(wire, start, end);
        check(wire.routePoints().size() >= 5, "legacy route gained explicit hidden corners before stale-index drag regression");
        check(EditorWireRouting.moveRoutePoint(wire, start, end, 1, 78, 48),
                "stale legacy route-point index remains resolvable after materialization");
        RoutePoint moved = wire.routePoints().get(3);
        check(moved.x() == 78 && moved.y() == 48,
                "legacy corner drag moves the visible corner that was clicked, not an inserted normalization corner");
        assertOrthogonal(wire, start, end, "legacy corner drag remains orthogonal after stale-index remap");
    }

    private static void legacySegmentHandleTargetsClickedLeg() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(96, 54);
        wire.setRoutePoints(List.of(new RoutePoint(42, 30), new RoutePoint(72, 42)));

        // Old segment index 0 represented both hidden L legs. The cursor is on its vertical leg at X=42.
        int movable = EditorWireRouting.addSegmentHandles(wire, start, end, 0, 42, 18);
        check(movable >= 0, "double-clicking a legacy hidden-L leg adds handles after canonicalization");
        boolean foundVerticalHandle = false;
        for (RoutePoint point : wire.routePoints()) {
            if (point.x() == 42 && (point.y() == 12 || point.y() == 24)) foundVerticalHandle = true;
        }
        check(foundVerticalHandle,
                "legacy segment double-click targets the visible leg under the cursor instead of stale direct index 0");
        assertOrthogonal(wire, start, end, "legacy segment handle insertion remains orthogonal");
    }

    private static void endpointSegmentBecomesDraggable() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        var start = new EditorWireRouting.Point(0, 0);
        var end = new EditorWireRouting.Point(72, 0);
        int movable = EditorWireRouting.prepareSegmentDrag(wire, start, end, 0, 36, 0);
        check(movable == 1, "dragging a straight endpoint-to-endpoint trace creates an interior draggable span");
        check(wire.routePoints().size() == 2, "straight trace gains exactly two route handles when direct drag begins");
        check(wire.routePoints().get(0).y() == 0 && wire.routePoints().get(1).y() == 0,
                "new drag handles initially preserve the exact visible straight route");
    }

    private static void segmentMovesPerpendicular() {
        WireConnection horizontal = new WireConnection(1, 0, 2, 0);
        horizontal.setRoutePoints(List.of(new RoutePoint(24, 0), new RoutePoint(48, 0)));
        check(EditorWireRouting.moveSegment(horizontal, 1, 0, 12), "horizontal interior segment is draggable");
        check(horizontal.routePoints().get(0).y() == 12 && horizontal.routePoints().get(1).y() == 12,
                "horizontal segment moves only vertically");
        check(horizontal.routePoints().get(0).x() == 24 && horizontal.routePoints().get(1).x() == 48,
                "horizontal segment keeps its X span while moving");
        check(EditorWireRouting.moveSegmentTo(horizontal, 1, 999, 31), "absolute mouse drag updates horizontal segment");
        check(horizontal.routePoints().get(0).y() == 30 && horizontal.routePoints().get(1).y() == 30,
                "absolute segment drag snaps directly to cursor row even for tiny incremental mouse events");

        WireConnection vertical = new WireConnection(1, 0, 2, 0);
        vertical.setRoutePoints(List.of(new RoutePoint(30, 18), new RoutePoint(30, 54)));
        check(EditorWireRouting.moveSegment(vertical, 1, 12, 0), "vertical interior segment is draggable");
        check(vertical.routePoints().get(0).x() == 42 && vertical.routePoints().get(1).x() == 42,
                "vertical segment moves only horizontally");
    }

    private static void viaIndicesFollowInsertedHandles() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        wire.setRoutePoints(List.of(
                new RoutePoint(18, 0),
                new RoutePoint(18, 36),
                new RoutePoint(60, 36)
        ));
        wire.setViaRouteIndices(List.of(1, 2));
        EditorWireRouting.insertRoutePoints(wire, 1, List.of(
                new RoutePoint(18, 12),
                new RoutePoint(18, 24)
        ));
        check(wire.viaRouteIndices().equals(List.of(3, 4)),
                "inserting route handles shifts PCB via indices instead of moving vias to unrelated corners");
    }

    private static void assertOrthogonal(WireConnection wire, EditorWireRouting.Point start, EditorWireRouting.Point end, String message) {
        List<EditorWireRouting.Point> full = EditorWireRouting.fullPoints(wire, start, end, false);
        for (int i = 0; i + 1 < full.size(); i++) {
            var a = full.get(i);
            var b = full.get(i + 1);
            check(aligned(a.x(), b.x()) || aligned(a.y(), b.y()), message);
        }
    }

    private static boolean aligned(double a, double b) {
        return Math.abs(a - b) < 0.001;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
