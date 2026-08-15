package com.foreverspark.logicsim.client.screen.v2;

/** Small dependency-free geometry helpers for Editor V2 wire selection. */
public final class EditorWireGeometry {
    private EditorWireGeometry() {}

    /**
     * Returns true when any part of a line segment touches or crosses the selection rectangle.
     * Editor traces are orthogonal, but this clipping test also handles diagonal inputs safely.
     */
    public static boolean segmentIntersectsRect(
            double x1, double y1, double x2, double y2,
            double left, double right, double top, double bottom
    ) {
        double l = Math.min(left, right);
        double r = Math.max(left, right);
        double t = Math.min(top, bottom);
        double b = Math.max(top, bottom);

        if (inside(x1, y1, l, r, t, b) || inside(x2, y2, l, r, t, b)) return true;
        if (Math.max(x1, x2) < l || Math.min(x1, x2) > r || Math.max(y1, y2) < t || Math.min(y1, y2) > b) return false;

        double dx = x2 - x1;
        double dy = y2 - y1;
        if (Math.abs(dx) < 0.000001) return x1 >= l && x1 <= r;
        if (Math.abs(dy) < 0.000001) return y1 >= t && y1 <= b;

        return crossesVertical(x1, y1, dx, dy, l, t, b)
                || crossesVertical(x1, y1, dx, dy, r, t, b)
                || crossesHorizontal(x1, y1, dx, dy, t, l, r)
                || crossesHorizontal(x1, y1, dx, dy, b, l, r);
    }

    private static boolean inside(double x, double y, double left, double right, double top, double bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static boolean crossesVertical(double x, double y, double dx, double dy, double edgeX, double top, double bottom) {
        double u = (edgeX - x) / dx;
        if (u < 0.0 || u > 1.0) return false;
        double hitY = y + u * dy;
        return hitY >= top && hitY <= bottom;
    }

    private static boolean crossesHorizontal(double x, double y, double dx, double dy, double edgeY, double left, double right) {
        double u = (edgeY - y) / dy;
        if (u < 0.0 || u > 1.0) return false;
        double hitX = x + u * dx;
        return hitX >= left && hitX <= right;
    }
}
