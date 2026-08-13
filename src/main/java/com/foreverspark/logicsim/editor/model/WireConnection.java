package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A logical connection plus optional presentation-only orthogonal route points.
 * Route points never change circuit semantics; the compiler only uses the endpoints.
 */
public final class WireConnection {
    private int sourceNodeId;
    private int sourcePort;
    private int targetNodeId;
    private int targetPort;
    private List<RoutePoint> routePoints = new ArrayList<>();

    public WireConnection() {
    }

    public WireConnection(int sourceNodeId, int sourcePort, int targetNodeId, int targetPort) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePort = sourcePort;
        this.targetNodeId = targetNodeId;
        this.targetPort = targetPort;
    }

    public int sourceNodeId() {
        return sourceNodeId;
    }

    public int sourcePort() {
        return sourcePort;
    }

    public int targetNodeId() {
        return targetNodeId;
    }

    public int targetPort() {
        return targetPort;
    }

    public List<RoutePoint> routePoints() {
        if (routePoints == null) {
            routePoints = new ArrayList<>();
        }
        return routePoints;
    }

    public void setRoutePoints(List<RoutePoint> routePoints) {
        this.routePoints = routePoints == null ? new ArrayList<>() : new ArrayList<>(routePoints);
    }

    public void clearRoutePoints() {
        routePoints().clear();
    }

    public void normalize() {
        if (routePoints == null) {
            routePoints = new ArrayList<>();
        }
        routePoints.removeIf(point -> point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof WireConnection other)) return false;
        return sourceNodeId == other.sourceNodeId
                && sourcePort == other.sourcePort
                && targetNodeId == other.targetNodeId
                && targetPort == other.targetPort
                && Objects.equals(routePoints(), other.routePoints());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, sourcePort, targetNodeId, targetPort, routePoints());
    }
}
