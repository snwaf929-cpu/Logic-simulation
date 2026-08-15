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
        List<EditorWireRouting.Point> full = EditorWireRouting.fullPoints(wire, start, end, false);
        check(!route.isEmpty(), "manual click route produces persistent route points");
        for (int i = 0; i + 1 < full.size(); i++) {
            var a = full.get(i);
            var b = full.get(i + 1);
            check(aligned(a.x(), b.x()) || aligned(a.y(), b.y()), "persisted V2.1B route contains no hidden diagonal segment");
            check(EditorGrid.aligned(a.x()) && EditorGrid.aligned(a.y()), "manual route stays on editor grid");
        }
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

    private static boolean aligned(double a, double b) {
        return Math.abs(a - b) < 0.001;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
