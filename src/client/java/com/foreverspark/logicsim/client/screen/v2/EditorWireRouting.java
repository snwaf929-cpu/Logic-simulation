package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical, grid-snapped route manipulation used by Editor V2.1B.
 *
 * <p>The logical wire endpoints never change here. This class owns only presentation routing metadata,
 * keeps every persisted segment orthogonal, and shifts PCB via indices whenever route points are inserted.</p>
 */
public final class EditorWireRouting {
    private static final double EPSILON = 0.001;

    private EditorWireRouting() {}

    public record Point(double x, double y) {}
    public record Segment(int index, Point a, Point b) {}

    /** Route used for selection handles before an autorouted wire has been manually edited. */
    public static List<RoutePoint> visibleRoute(WireConnection wire, Point start, Point end) {
        if (wire == null) return List.of();
        if (!wire.routePoints().isEmpty()) return List.copyOf(wire.routePoints());
        return autoRoute(start, end);
    }

    /** Materialize the current autoroute without changing its visible shape. */
    public static void materialize(WireConnection wire, Point start, Point end) {
        if (wire == null || !wire.routePoints().isEmpty()) return;
        wire.setRoutePoints(autoRoute(start, end));
    }

    /**
     * Default route is deliberately simple: aligned pins stay straight; otherwise a centered vertical trunk is used.
     */
    public static List<RoutePoint> autoRoute(Point start, Point end) {
        if (start == null || end == null) return List.of();
        if (aligned(start.x, end.x) || aligned(start.y, end.y)) return List.of();
        double midX = EditorGrid.snap((start.x + end.x) * 0.5);
        return List.of(
                new RoutePoint(midX, EditorGrid.snap(start.y)),
                new RoutePoint(midX, EditorGrid.snap(end.y))
        );
    }

    /** Convert arbitrary click waypoints into explicit orthogonal route points. */
    public static List<RoutePoint> explicitRoute(Point start, List<Point> waypoints, Point end) {
        if (start == null || end == null) return List.of();
        ArrayList<Point> all = new ArrayList<>();
        all.add(snap(start));
        if (waypoints != null) for (Point point : waypoints) if (point != null) all.add(snap(point));
        all.add(snap(end));

        ArrayList<Point> expanded = new ArrayList<>();
        appendUnique(expanded, all.getFirst());
        for (int index = 1; index < all.size(); index++) {
            Point a = expanded.getLast();
            Point b = all.get(index);
            if (!aligned(a.x, b.x) && !aligned(a.y, b.y)) {
                appendUnique(expanded, new Point(b.x, a.y));
            }
            appendUnique(expanded, b);
        }
        removeCollinearInterior(expanded);

        ArrayList<RoutePoint> result = new ArrayList<>();
        for (int index = 1; index + 1 < expanded.size(); index++) {
            Point point = expanded.get(index);
            result.add(new RoutePoint(point.x, point.y));
        }
        return List.copyOf(result);
    }

    public static List<Point> fullPoints(WireConnection wire, Point start, Point end, boolean includeVirtualAutoRoute) {
        ArrayList<Point> result = new ArrayList<>();
        result.add(snap(start));
        List<RoutePoint> route = wire == null ? List.of()
                : (wire.routePoints().isEmpty() && includeVirtualAutoRoute ? autoRoute(start, end) : wire.routePoints());
        for (RoutePoint point : route) result.add(new Point(point.x(), point.y()));
        result.add(snap(end));
        return List.copyOf(result);
    }

    public static List<Segment> segments(WireConnection wire, Point start, Point end, boolean includeVirtualAutoRoute) {
        List<Point> points = fullPoints(wire, start, end, includeVirtualAutoRoute);
        ArrayList<Segment> result = new ArrayList<>();
        for (int index = 0; index + 1 < points.size(); index++) {
            Point a = points.get(index);
            Point b = points.get(index + 1);
            if (aligned(a.x, b.x) || aligned(a.y, b.y)) {
                if (!same(a, b)) result.add(new Segment(index, a, b));
            } else {
                Point corner = new Point(b.x, a.y);
                if (!same(a, corner)) result.add(new Segment(index, a, corner));
                if (!same(corner, b)) result.add(new Segment(index, corner, b));
            }
        }
        return List.copyOf(result);
    }

    /** Move one persisted corner while preserving orthogonality to its neighbors. */
    public static boolean moveRoutePoint(WireConnection wire, Point start, Point end, int routeIndex, double worldX, double worldY) {
        if (wire == null) return false;
        materialize(wire, start, end);
        List<RoutePoint> route = wire.routePoints();
        if (routeIndex < 0 || routeIndex >= route.size()) return false;

        List<Point> full = fullPoints(wire, start, end, false);
        int fullIndex = routeIndex + 1;
        Point prev = full.get(fullIndex - 1);
        Point current = full.get(fullIndex);
        Point next = full.get(fullIndex + 1);
        boolean prevHorizontal = aligned(prev.y, current.y);
        boolean nextHorizontal = aligned(next.y, current.y);
        double x = EditorGrid.snap(worldX);
        double y = EditorGrid.snap(worldY);

        if (prevHorizontal && !nextHorizontal) {
            if (routeIndex > 0) {
                RoutePoint previous = route.get(routeIndex - 1);
                route.set(routeIndex - 1, new RoutePoint(previous.x(), y));
            } else {
                y = prev.y;
            }
            if (routeIndex + 1 < route.size()) {
                RoutePoint following = route.get(routeIndex + 1);
                route.set(routeIndex + 1, new RoutePoint(x, following.y()));
            } else {
                x = next.x;
            }
            route.set(routeIndex, new RoutePoint(x, y));
        } else if (!prevHorizontal && nextHorizontal) {
            if (routeIndex > 0) {
                RoutePoint previous = route.get(routeIndex - 1);
                route.set(routeIndex - 1, new RoutePoint(x, previous.y()));
            } else {
                x = prev.x;
            }
            if (routeIndex + 1 < route.size()) {
                RoutePoint following = route.get(routeIndex + 1);
                route.set(routeIndex + 1, new RoutePoint(following.x(), y));
            } else {
                y = next.y;
            }
            route.set(routeIndex, new RoutePoint(x, y));
        } else if (prevHorizontal) {
            route.set(routeIndex, new RoutePoint(x, current.y));
        } else {
            route.set(routeIndex, new RoutePoint(current.x, y));
        }
        snapAndAlign(wire, start, end);
        return true;
    }

    /** Only interior segments can move directly; endpoint segments are converted to a small draggable span first. */
    public static int prepareSegmentDrag(WireConnection wire, Point start, Point end, int segmentIndex, double worldX, double worldY) {
        if (wire == null) return -1;
        materialize(wire, start, end);
        int routeCount = wire.routePoints().size();
        if (segmentIndex >= 1 && segmentIndex < routeCount) return segmentIndex;

        List<Point> full = fullPoints(wire, start, end, false);
        if (segmentIndex < 0 || segmentIndex + 1 >= full.size()) return -1;
        Point a = full.get(segmentIndex);
        Point b = full.get(segmentIndex + 1);
        if (!aligned(a.x, b.x) && !aligned(a.y, b.y)) return -1;

        Point[] anchors = anchorsAround(a, b, worldX, worldY);
        if (anchors == null) return -1;
        int insertion = Math.max(0, Math.min(wire.routePoints().size(), segmentIndex));
        insertRoutePoints(wire, insertion, List.of(
                new RoutePoint(anchors[0].x, anchors[0].y),
                new RoutePoint(anchors[1].x, anchors[1].y)
        ));
        snapAndAlign(wire, start, end);
        return insertion + 1;
    }

    public static boolean moveSegment(WireConnection wire, int directSegmentIndex, double dx, double dy) {
        if (wire == null) return false;
        List<RoutePoint> route = wire.routePoints();
        if (directSegmentIndex < 1 || directSegmentIndex >= route.size()) return false;
        int first = directSegmentIndex - 1;
        int second = directSegmentIndex;
        RoutePoint a = route.get(first);
        RoutePoint b = route.get(second);
        if (aligned(a.y(), b.y())) {
            double delta = EditorGrid.snap(a.y() + dy) - a.y();
            route.set(first, new RoutePoint(a.x(), a.y() + delta));
            route.set(second, new RoutePoint(b.x(), b.y() + delta));
            return true;
        }
        if (aligned(a.x(), b.x())) {
            double delta = EditorGrid.snap(a.x() + dx) - a.x();
            route.set(first, new RoutePoint(a.x() + delta, a.y()));
            route.set(second, new RoutePoint(b.x() + delta, b.y()));
            return true;
        }
        return false;
    }

    /** Double-click creates two persistent handles on that segment; the new middle span can then be dragged. */
    public static int addSegmentHandles(WireConnection wire, Point start, Point end, int segmentIndex, double worldX, double worldY) {
        if (wire == null) return -1;
        materialize(wire, start, end);
        List<Point> full = fullPoints(wire, start, end, false);
        if (segmentIndex < 0 || segmentIndex + 1 >= full.size()) return -1;
        Point[] anchors = anchorsAround(full.get(segmentIndex), full.get(segmentIndex + 1), worldX, worldY);
        if (anchors == null) return -1;
        int insertion = Math.max(0, Math.min(wire.routePoints().size(), segmentIndex));
        insertRoutePoints(wire, insertion, List.of(
                new RoutePoint(anchors[0].x, anchors[0].y),
                new RoutePoint(anchors[1].x, anchors[1].y)
        ));
        snapAndAlign(wire, start, end);
        return insertion + 1;
    }

    public static void snapAndAlign(WireConnection wire, Point start, Point end) {
        if (wire == null) return;
        List<RoutePoint> route = wire.routePoints();
        for (int index = 0; index < route.size(); index++) {
            RoutePoint point = route.get(index);
            route.set(index, new RoutePoint(EditorGrid.snap(point.x()), EditorGrid.snap(point.y())));
        }
        if (route.isEmpty()) return;
        RoutePoint first = route.getFirst();
        Point snappedStart = snap(start);
        if (aligned(first.y(), snappedStart.y())) route.set(0, new RoutePoint(first.x(), snappedStart.y));
        else if (aligned(first.x(), snappedStart.x())) route.set(0, new RoutePoint(snappedStart.x, first.y()));

        RoutePoint last = route.getLast();
        Point snappedEnd = snap(end);
        if (aligned(last.y(), snappedEnd.y())) route.set(route.size() - 1, new RoutePoint(last.x(), snappedEnd.y));
        else if (aligned(last.x(), snappedEnd.x())) route.set(route.size() - 1, new RoutePoint(snappedEnd.x, last.y()));
    }

    /** Insert route metadata without causing existing PCB vias to jump to a different corner. */
    public static void insertRoutePoints(WireConnection wire, int insertionIndex, List<RoutePoint> points) {
        if (wire == null || points == null || points.isEmpty()) return;
        int insertion = Math.max(0, Math.min(wire.routePoints().size(), insertionIndex));
        int count = points.size();
        ArrayList<Integer> shiftedVias = new ArrayList<>();
        for (int via : wire.viaRouteIndices()) shiftedVias.add(via >= insertion ? via + count : via);
        wire.routePoints().addAll(insertion, points);
        wire.setViaRouteIndices(shiftedVias);
    }

    private static Point[] anchorsAround(Point a, Point b, double worldX, double worldY) {
        if (a == null || b == null) return null;
        if (aligned(a.y, b.y)) {
            double low = Math.min(a.x, b.x);
            double high = Math.max(a.x, b.x);
            if (high - low < EditorGrid.STEP * 2.0) return null;
            double center = clamp(EditorGrid.snap(worldX), low + EditorGrid.STEP, high - EditorGrid.STEP);
            double p1 = clamp(EditorGrid.snap(center - EditorGrid.STEP), low, high);
            double p2 = clamp(EditorGrid.snap(center + EditorGrid.STEP), low, high);
            if (aligned(p1, p2)) return null;
            if (b.x < a.x) { double swap = p1; p1 = p2; p2 = swap; }
            return new Point[]{new Point(p1, a.y), new Point(p2, a.y)};
        }
        if (aligned(a.x, b.x)) {
            double low = Math.min(a.y, b.y);
            double high = Math.max(a.y, b.y);
            if (high - low < EditorGrid.STEP * 2.0) return null;
            double center = clamp(EditorGrid.snap(worldY), low + EditorGrid.STEP, high - EditorGrid.STEP);
            double p1 = clamp(EditorGrid.snap(center - EditorGrid.STEP), low, high);
            double p2 = clamp(EditorGrid.snap(center + EditorGrid.STEP), low, high);
            if (aligned(p1, p2)) return null;
            if (b.y < a.y) { double swap = p1; p1 = p2; p2 = swap; }
            return new Point[]{new Point(a.x, p1), new Point(a.x, p2)};
        }
        return null;
    }

    private static Point snap(Point point) {
        return new Point(EditorGrid.snap(point.x), EditorGrid.snap(point.y));
    }

    private static void appendUnique(List<Point> points, Point next) {
        if (points.isEmpty() || !same(points.getLast(), next)) points.add(next);
    }

    private static void removeCollinearInterior(List<Point> points) {
        for (int index = points.size() - 2; index > 0; index--) {
            Point a = points.get(index - 1);
            Point b = points.get(index);
            Point c = points.get(index + 1);
            if ((aligned(a.x, b.x) && aligned(b.x, c.x)) || (aligned(a.y, b.y) && aligned(b.y, c.y))) {
                points.remove(index);
            }
        }
    }

    private static boolean same(Point a, Point b) {
        return aligned(a.x, b.x) && aligned(a.y, b.y);
    }

    private static boolean aligned(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
