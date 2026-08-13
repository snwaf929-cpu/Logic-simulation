package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A logical connection plus optional presentation-only orthogonal route points.
 * Route points and branchStart never change circuit semantics; the compiler only uses endpoints.
 */
public final class WireConnection {
    private int sourceNodeId;
    private int sourcePort;
    private int targetNodeId;
    private int targetPort;
    private List<RoutePoint> routePoints = new ArrayList<>();
    private RoutePoint branchStart;

    public WireConnection() {
    }

    public WireConnection(int sourceNodeId, int sourcePort, int targetNodeId, int targetPort) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePort = sourcePort;
        this.targetNodeId = targetNodeId;
        this.targetPort = targetPort;
    }

    public int sourceNodeId() { return sourceNodeId; }
    public int sourcePort() { return sourcePort; }
    public int targetNodeId() { return targetNodeId; }
    public int targetPort() { return targetPort; }

    public List<RoutePoint> routePoints() {
        if (routePoints == null) routePoints = new ArrayList<>();
        return routePoints;
    }

    public void setRoutePoints(List<RoutePoint> routePoints) {
        this.routePoints = routePoints == null ? new ArrayList<>() : new ArrayList<>(routePoints);
    }

    public void clearRoutePoints() { routePoints().clear(); }

    /**
     * Visual tap point used when this connection branches from another wire.
     * The logical source remains sourceNodeId/sourcePort, so all branches share the exact same signal.
     */
    public RoutePoint branchStart() { return branchStart; }
    public void setBranchStart(RoutePoint branchStart) { this.branchStart = branchStart; }
    public void clearBranchStart() { this.branchStart = null; }

    public void normalize() {
        if (routePoints == null) routePoints = new ArrayList<>();
        routePoints.removeIf(point -> point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()));
        if (branchStart != null && (!Double.isFinite(branchStart.x()) || !Double.isFinite(branchStart.y()))) {
            branchStart = null;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof WireConnection other)) return false;
        return sourceNodeId == other.sourceNodeId
                && sourcePort == other.sourcePort
                && targetNodeId == other.targetNodeId
                && targetPort == other.targetPort
                && Objects.equals(routePoints(), other.routePoints())
                && Objects.equals(branchStart, other.branchStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, sourcePort, targetNodeId, targetPort, routePoints(), branchStart);
    }
}
