package com.foreverspark.logicsim.editor.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure model operations for reusable BOARD template instances. */
public final class BoardTemplateEngine {
    private BoardTemplateEngine() {}

    public static InsertResult insert(CircuitDocument target, BoardTemplateDefinition template, double originX, double originY) {
        if (target == null) throw new IllegalArgumentException("Destination board is required");
        if (template == null) throw new IllegalArgumentException("BOARD template is required");
        target.normalize();
        template.normalize();
        if (template.name.isBlank()) throw new IllegalArgumentException("BOARD template name is required");
        if (template.circuit.nodes.isEmpty()) throw new IllegalArgumentException("BOARD template is empty");

        double minX = template.circuit.nodes.stream().mapToDouble(node -> node.x).min().orElse(0.0);
        double minY = template.circuit.nodes.stream().mapToDouble(node -> node.y).min().orElse(0.0);
        double dx = originX - minX;
        double dy = originY - minY;
        int instanceId = Math.max(1, target.nextTemplateInstanceId++);

        Map<Integer, Integer> ids = new LinkedHashMap<>();
        List<Integer> inserted = new ArrayList<>();
        for (EditorNode source : template.circuit.nodes) {
            EditorNode node = target.addNode(source.kind, source.x + dx, source.y + dy);
            copyNodeFields(source, node);
            node.templateInstanceId = instanceId;
            node.templateName = template.name;
            ids.put(source.id, node.id);
            inserted.add(node.id);
        }

        for (WireConnection source : template.circuit.wires) {
            Integer sourceId = ids.get(source.sourceNodeId());
            Integer targetId = ids.get(source.targetNodeId());
            if (sourceId == null || targetId == null) {
                throw new IllegalArgumentException("BOARD template contains a wire outside its own node set");
            }
            target.connect(sourceId, source.sourcePort(), targetId, source.targetPort());
            WireConnection wire = target.wires.getLast();
            copyWirePresentation(source, wire, dx, dy);
        }
        target.normalize();
        return new InsertResult(instanceId, template.name, List.copyOf(inserted), Map.copyOf(ids));
    }

    public static BoardTemplateReplacementPreview previewReplacement(
            CircuitDocument document,
            int instanceId,
            BoardTemplateDefinition replacement
    ) {
        if (document == null) throw new IllegalArgumentException("Board is required");
        if (replacement == null) throw new IllegalArgumentException("Replacement template is required");
        document.normalize();
        replacement.normalize();

        Set<Integer> instanceNodes = instanceNodeIds(document, instanceId);
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (instanceNodes.isEmpty()) {
            errors.add("Template instance " + instanceId + " no longer exists");
            return new BoardTemplateReplacementPreview(instanceId, "", replacement.name, List.of(), warnings, errors, 0);
        }

        List<BoardSocketSpec> oldSockets = instanceSockets(document, instanceId);
        List<BoardSocketSpec> newSockets = replacement.sockets();
        Map<Integer, BoardSocketSpec> oldByNode = new HashMap<>();
        for (BoardSocketSpec socket : oldSockets) oldByNode.put(socket.nodeId(), socket);
        Map<Integer, Integer> externalCounts = new HashMap<>();
        int externalConnections = 0;

        for (WireConnection wire : document.wires) {
            boolean sourceInside = instanceNodes.contains(wire.sourceNodeId());
            boolean targetInside = instanceNodes.contains(wire.targetNodeId());
            if (sourceInside == targetInside) continue;
            externalConnections++;
            int boundaryNode = sourceInside ? wire.sourceNodeId() : wire.targetNodeId();
            BoardSocketSpec socket = oldByNode.get(boundaryNode);
            if (socket == null) {
                errors.add("External connection bypasses sockets at node " + boundaryNode);
                continue;
            }
            externalCounts.merge(boundaryNode, 1, Integer::sum);
            if (sourceInside && socket.direction() != PortDirection.OUTPUT) {
                errors.add("INPUT socket " + socket.name() + " is incorrectly driving outside the instance");
            }
            if (targetInside && socket.direction() != PortDirection.INPUT) {
                errors.add("OUTPUT socket " + socket.name() + " is incorrectly driven from outside the instance");
            }
        }

        Map<Integer, BoardSocketSpec> mapped = new LinkedHashMap<>();
        Set<Integer> usedNewNodeIds = new HashSet<>();
        Set<Integer> identityLockedOld = new HashSet<>();

        // Stable interface identity wins. If an identity exists on both sides but its contract changed,
        // reject instead of silently remapping that identity by label.
        for (BoardSocketSpec oldSocket : oldSockets) {
            BoardSocketSpec identity = null;
            for (BoardSocketSpec candidate : newSockets) {
                if (!candidate.interfaceId().equalsIgnoreCase(oldSocket.interfaceId())) continue;
                identity = candidate;
                break;
            }
            if (identity == null) continue;
            identityLockedOld.add(oldSocket.nodeId());
            if (identity.direction() != oldSocket.direction() || identity.width() != oldSocket.width()) {
                errors.add("Interface " + oldSocket.interfaceId() + " changed contract: "
                        + oldSocket.direction() + "[" + oldSocket.width() + "] -> "
                        + identity.direction() + "[" + identity.width() + "]");
                continue;
            }
            mapped.put(oldSocket.nodeId(), identity);
            usedNewNodeIds.add(identity.nodeId());
        }

        // Renamed/re-authored templates can still reconnect by an unambiguous name+direction+width signature.
        for (BoardSocketSpec oldSocket : oldSockets) {
            if (mapped.containsKey(oldSocket.nodeId()) || identityLockedOld.contains(oldSocket.nodeId())) continue;
            List<BoardSocketSpec> candidates = new ArrayList<>();
            for (BoardSocketSpec candidate : newSockets) {
                if (usedNewNodeIds.contains(candidate.nodeId())) continue;
                if (oldSocket.sameSignature(candidate)) candidates.add(candidate);
            }
            if (candidates.size() == 1) {
                BoardSocketSpec match = candidates.getFirst();
                mapped.put(oldSocket.nodeId(), match);
                usedNewNodeIds.add(match.nodeId());
            } else if (candidates.size() > 1) {
                errors.add("Ambiguous replacement for socket " + oldSocket.signature());
            } else if (externalCounts.getOrDefault(oldSocket.nodeId(), 0) > 0) {
                errors.add("No compatible replacement for connected socket " + oldSocket.signature());
            } else {
                warnings.add("Unused old socket will be removed: " + oldSocket.signature());
            }
        }

        for (BoardSocketSpec socket : newSockets) {
            if (!usedNewNodeIds.contains(socket.nodeId())) warnings.add("New socket has no old mapping: " + socket.signature());
        }

        List<BoardTemplateReplacementPreview.SocketMapping> mappings = new ArrayList<>();
        for (BoardSocketSpec oldSocket : oldSockets) {
            BoardSocketSpec next = mapped.get(oldSocket.nodeId());
            if (next == null) continue;
            BoardTemplateReplacementPreview.MatchKind kind = oldSocket.interfaceId().equalsIgnoreCase(next.interfaceId())
                    ? BoardTemplateReplacementPreview.MatchKind.INTERFACE_ID
                    : BoardTemplateReplacementPreview.MatchKind.NAME_DIRECTION_WIDTH;
            mappings.add(new BoardTemplateReplacementPreview.SocketMapping(
                    oldSocket,
                    next,
                    kind,
                    externalCounts.getOrDefault(oldSocket.nodeId(), 0)
            ));
        }
        mappings.sort(Comparator.comparingInt(mapping -> mapping.oldSocket().order()));

        return new BoardTemplateReplacementPreview(
                instanceId,
                instanceTemplateName(document, instanceId),
                replacement.name,
                mappings,
                warnings,
                errors,
                externalConnections
        );
    }

    public static ReplaceResult replace(
            CircuitDocument document,
            int instanceId,
            BoardTemplateDefinition replacement
    ) {
        BoardTemplateReplacementPreview preview = previewReplacement(document, instanceId, replacement);
        if (!preview.compatible()) {
            throw new IllegalArgumentException("BOARD template replacement is incompatible: " + String.join("; ", preview.errors()));
        }

        Set<Integer> oldNodes = instanceNodeIds(document, instanceId);
        if (oldNodes.isEmpty()) throw new IllegalArgumentException("Template instance no longer exists: " + instanceId);
        double originX = Double.POSITIVE_INFINITY;
        double originY = Double.POSITIVE_INFINITY;
        for (int nodeId : oldNodes) {
            EditorNode node = document.node(nodeId);
            originX = Math.min(originX, node.x);
            originY = Math.min(originY, node.y);
        }

        Map<Integer, BoardSocketSpec> mappedOld = new HashMap<>();
        for (var mapping : preview.mappings()) mappedOld.put(mapping.oldSocket().nodeId(), mapping.newSocket());
        List<ExternalWire> external = new ArrayList<>();
        for (WireConnection wire : document.wires) {
            boolean sourceInside = oldNodes.contains(wire.sourceNodeId());
            boolean targetInside = oldNodes.contains(wire.targetNodeId());
            if (sourceInside == targetInside) continue;
            int boundaryNode = sourceInside ? wire.sourceNodeId() : wire.targetNodeId();
            BoardSocketSpec mappedSocket = mappedOld.get(boundaryNode);
            if (mappedSocket == null) {
                throw new IllegalArgumentException("Connected old socket has no replacement mapping at node " + boundaryNode);
            }
            external.add(ExternalWire.capture(wire, sourceInside, mappedSocket.nodeId()));
        }

        List<Integer> oldNodeList = new ArrayList<>(oldNodes);
        for (int nodeId : oldNodeList) document.removeNode(nodeId);
        InsertResult inserted = insert(document, replacement, originX, originY);

        for (ExternalWire saved : external) {
            Integer replacementSocketNode = inserted.sourceToInsertedNodeId().get(saved.replacementTemplateSocketNodeId());
            if (replacementSocketNode == null) {
                throw new IllegalStateException("Replacement socket node was not inserted: " + saved.replacementTemplateSocketNodeId());
            }
            int sourceNodeId = saved.instanceWasSource() ? replacementSocketNode : saved.sourceNodeId();
            int targetNodeId = saved.instanceWasSource() ? saved.targetNodeId() : replacementSocketNode;
            document.connect(sourceNodeId, saved.sourcePort(), targetNodeId, saved.targetPort());
            WireConnection wire = document.wires.getLast();
            saved.applyPresentation(wire);
        }
        document.normalize();
        return new ReplaceResult(preview, inserted);
    }

    public static Set<Integer> instanceNodeIds(CircuitDocument document, int instanceId) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        if (document == null || instanceId <= 0) return result;
        for (EditorNode node : document.nodes) if (node.templateInstanceId == instanceId) result.add(node.id);
        return result;
    }

    public static List<BoardSocketSpec> instanceSockets(CircuitDocument document, int instanceId) {
        List<BoardSocketSpec> result = new ArrayList<>();
        if (document == null || instanceId <= 0) return result;
        for (EditorNode node : document.nodes) {
            if (node.templateInstanceId != instanceId || !node.isBoardSocket()) continue;
            result.add(new BoardSocketSpec(node.interfaceId, node.label, node.socketDirection, node.width, node.interfaceOrder, node.id));
        }
        result.sort(Comparator.comparingInt(BoardSocketSpec::order).thenComparingInt(BoardSocketSpec::nodeId));
        return List.copyOf(result);
    }

    public static String instanceTemplateName(CircuitDocument document, int instanceId) {
        if (document == null || instanceId <= 0) return "";
        for (EditorNode node : document.nodes) {
            if (node.templateInstanceId == instanceId) return node.templateName == null ? "" : node.templateName;
        }
        return "";
    }

    private static void copyNodeFields(EditorNode source, EditorNode target) {
        target.width = source.width;
        target.laneWidth = source.laneWidth;
        target.label = source.label == null ? "" : source.label;
        target.chipName = source.chipName == null ? "" : source.chipName;
        target.constantValue = source.constantValue;
        target.inputDefaultValue = source.inputDefaultValue;
        target.clockSource = source.clockSource;
        target.clockFrequencyHz = source.clockFrequencyHz;
        target.randomSource = source.randomSource;
        target.randomChancePercent = source.randomChancePercent;
        target.slices = new ArrayList<>();
        if (source.slices != null) for (BusSliceOutput slice : source.slices) if (slice != null) target.slices.add(slice.copy());
        target.boardSocket = source.boardSocket;
        target.interfaceId = source.interfaceId == null ? "" : source.interfaceId;
        target.socketDirection = source.socketDirection == null ? PortDirection.INPUT : source.socketDirection;
        target.interfaceOrder = source.interfaceOrder;
    }

    private static void copyWirePresentation(WireConnection source, WireConnection target, double dx, double dy) {
        List<RoutePoint> route = new ArrayList<>();
        for (RoutePoint point : source.routePoints()) route.add(new RoutePoint(point.x() + dx, point.y() + dy));
        target.setRoutePoints(route);
        RoutePoint branch = source.branchStart();
        if (branch != null) target.setBranchStart(new RoutePoint(branch.x() + dx, branch.y() + dy));
        target.setLayer(source.layer());
        target.setViaRouteIndices(source.viaRouteIndices());
    }

    public record InsertResult(
            int instanceId,
            String templateName,
            List<Integer> nodeIds,
            Map<Integer, Integer> sourceToInsertedNodeId
    ) {
        public InsertResult {
            nodeIds = List.copyOf(nodeIds);
            sourceToInsertedNodeId = Map.copyOf(sourceToInsertedNodeId);
        }
    }

    public record ReplaceResult(BoardTemplateReplacementPreview preview, InsertResult inserted) {}

    private record ExternalWire(
            boolean instanceWasSource,
            int replacementTemplateSocketNodeId,
            int sourceNodeId,
            int sourcePort,
            int targetNodeId,
            int targetPort,
            List<RoutePoint> routePoints,
            RoutePoint branchStart,
            WireLayer layer,
            List<Integer> viaRouteIndices
    ) {
        static ExternalWire capture(WireConnection wire, boolean instanceWasSource, int replacementTemplateSocketNodeId) {
            List<RoutePoint> route = new ArrayList<>();
            for (RoutePoint point : wire.routePoints()) route.add(new RoutePoint(point.x(), point.y()));
            RoutePoint branch = wire.branchStart();
            RoutePoint branchCopy = branch == null ? null : new RoutePoint(branch.x(), branch.y());
            return new ExternalWire(
                    instanceWasSource,
                    replacementTemplateSocketNodeId,
                    wire.sourceNodeId(), wire.sourcePort(), wire.targetNodeId(), wire.targetPort(),
                    List.copyOf(route), branchCopy, wire.layer(), List.copyOf(wire.viaRouteIndices())
            );
        }

        void applyPresentation(WireConnection wire) {
            wire.setRoutePoints(routePoints);
            wire.setBranchStart(branchStart);
            wire.setLayer(layer);
            wire.setViaRouteIndices(viaRouteIndices);
        }
    }
}
