package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A logical connection plus presentation-only PCB routing metadata.
 * Route points, branchStart, copper layer, and vias never change circuit semantics;
 * the compiler only uses the logical endpoints.
 */
public final class WireConnection {
    private int sourceNodeId;
    private int sourcePort;
    private int targetNodeId;
    private int targetPort;
    private List<RoutePoint> routePoints = new ArrayList<>();
    private RoutePoint branchStart;
    private WireLayer layer = WireLayer.FRONT;
    private List<Integer> viaRouteIndices = new ArrayList<>();

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
        normalizeVias();
    }

    public void clearRoutePoints() {
        routePoints().clear();
        viaRouteIndices().clear();
    }

    /**
     * Visual tap point used when this connection branches from another wire.
     * The logical source remains sourceNodeId/sourcePort, so all branches share the exact same signal.
     */
    public RoutePoint branchStart() { return branchStart; }
    public void setBranchStart(RoutePoint branchStart) { this.branchStart = branchStart; }
    public void clearBranchStart() { this.branchStart = null; }

    /** Copper side used by the source-most segment of this trace. */
    public WireLayer layer() {
        if (layer == null) layer = WireLayer.FRONT;
        return layer;
    }

    public void setLayer(WireLayer layer) {
        this.layer = layer == null ? WireLayer.FRONT : layer;
    }

    public void flipBaseLayer() {
        setLayer(layer().opposite());
    }

    /**
     * Route-point indices containing a through-board via. A via at route index N changes
     * the copper side for every segment after that route point. Indices are sorted and unique.
     */
    public List<Integer> viaRouteIndices() {
        if (viaRouteIndices == null) viaRouteIndices = new ArrayList<>();
        return viaRouteIndices;
    }

    public void setViaRouteIndices(List<Integer> indices) {
        viaRouteIndices = indices == null ? new ArrayList<>() : new ArrayList<>(indices);
        normalizeVias();
    }

    public boolean hasViaAtRouteIndex(int routeIndex) {
        return viaRouteIndices().contains(routeIndex);
    }

    public boolean toggleViaAtRouteIndex(int routeIndex) {
        if (routeIndex < 0 || routeIndex >= routePoints().size()) return false;
        if (viaRouteIndices().remove(Integer.valueOf(routeIndex))) return false;
        viaRouteIndices().add(routeIndex);
        normalizeVias();
        return true;
    }

    /** Direct path segment 0 is source -> first route point; the last segment ends at the target. */
    public WireLayer segmentLayer(int directSegmentIndex) {
        WireLayer result = layer();
        int segment = Math.max(0, directSegmentIndex);
        for (int viaIndex : viaRouteIndices()) {
            if (viaIndex < segment) result = result.opposite();
            else break;
        }
        return result;
    }

    public void normalize() {
        if (routePoints == null) routePoints = new ArrayList<>();
        routePoints.removeIf(point -> point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()));
        if (branchStart != null && (!Double.isFinite(branchStart.x()) || !Double.isFinite(branchStart.y()))) {
            branchStart = null;
        }
        if (layer == null) layer = WireLayer.FRONT;
        normalizeVias();
    }

    private void normalizeVias() {
        if (viaRouteIndices == null) viaRouteIndices = new ArrayList<>();
        int routeCount = routePoints().size();
        TreeSet<Integer> valid = new TreeSet<>();
        for (Integer index : viaRouteIndices) {
            if (index != null && index >= 0 && index < routeCount) valid.add(index);
        }
        viaRouteIndices = new ArrayList<>(valid);
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
                && Objects.equals(branchStart, other.branchStart)
                && layer() == other.layer()
                && Objects.equals(viaRouteIndices(), other.viaRouteIndices());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, sourcePort, targetNodeId, targetPort,
                routePoints(), branchStart, layer(), viaRouteIndices());
    }
}
