package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.BoardSocketSpec;
import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.BoardTemplateEngine;
import com.foreverspark.logicsim.editor.model.BoardTemplateReplacementPreview;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.model.WireLayer;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.util.List;

/** Dependency-light BOARD template/socket regression checks for Logic Editor V2 Phase 4. */
public final class EditorV2Phase4Checks {
    private EditorV2Phase4Checks() {}

    public static void run() {
        socketInfrastructureChecks();
        deterministicOrderingChecks();
        interfaceValidationChecks();
        insertionChecks();
        identityMappingChecks();
        signatureFallbackChecks();
        mismatchRejectionChecks();
        externalWireAndPcbPreservationChecks();
        boundaryBypassRejectionChecks();
        snapshotChecks();
    }

    private static void socketInfrastructureChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 8;
        EditorNode socket = document.addNode(NodeKind.BUS, 72, 0);
        socket.width = 8;
        socket.configureBoardSocket("DATA", PortDirection.INPUT, 0);
        socket.interfaceId = "data-in";
        EditorNode output = document.addNode(NodeKind.OUTPUT, 144, 0);
        output.width = 8;
        document.connect(input.id, 0, socket.id, 0);
        document.connect(socket.id, 0, output.id, 0);

        check(socket.kind == NodeKind.BUS && socket.isBoardSocket(),
                "BOARD socket remains BUS routing infrastructure instead of becoming a primitive gate");
        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA5L);
        check(compiled.inputUnsigned(output.id, 0) == 0xA5L,
                "BOARD socket is electrically transparent BUS routing");
    }

    private static void deterministicOrderingChecks() {
        BoardTemplateDefinition template = passThroughTemplate("ORDERED", "data-in", "data-out", 8);
        EditorNode inputSocket = socketByDirection(template, PortDirection.INPUT);
        EditorNode outputSocket = socketByDirection(template, PortDirection.OUTPUT);
        inputSocket.interfaceOrder = 5;
        outputSocket.interfaceOrder = 2;
        template.normalize();
        List<BoardSocketSpec> sockets = template.sockets();
        check(sockets.size() == 2, "template exposes authored sockets only");
        check(sockets.get(0).direction() == PortDirection.OUTPUT && sockets.get(0).order() == 2,
                "explicit socket order determines first interface position");
        check(sockets.get(1).direction() == PortDirection.INPUT && sockets.get(1).order() == 5,
                "explicit socket order remains deterministic independent of node id");
    }

    private static void interfaceValidationChecks() {
        BoardTemplateDefinition duplicateId = passThroughTemplate("BAD_ID", "same", "same", 1);
        boolean identityRejected = false;
        try {
            duplicateId.normalize();
        } catch (IllegalArgumentException expected) {
            identityRejected = expected.getMessage() != null && expected.getMessage().contains("Duplicate BOARD socket interface identity");
        }
        check(identityRejected, "duplicate socket interface identities are rejected");

        BoardTemplateDefinition duplicateOrder = passThroughTemplate("BAD_ORDER", "a", "b", 1);
        socketByDirection(duplicateOrder, PortDirection.INPUT).interfaceOrder = 3;
        socketByDirection(duplicateOrder, PortDirection.OUTPUT).interfaceOrder = 3;
        boolean orderRejected = false;
        try {
            duplicateOrder.normalize();
        } catch (IllegalArgumentException expected) {
            orderRejected = expected.getMessage() != null && expected.getMessage().contains("Duplicate BOARD socket order");
        }
        check(orderRejected, "duplicate explicit socket orders are rejected");
    }

    private static void insertionChecks() {
        BoardTemplateDefinition template = passThroughTemplate("LINK8", "link-in", "link-out", 8);
        CircuitDocument board = new CircuitDocument();
        BoardTemplateEngine.InsertResult first = BoardTemplateEngine.insert(board, template, 120, 60);
        BoardTemplateEngine.InsertResult second = BoardTemplateEngine.insert(board, template, 360, 60);

        check(first.instanceId() > 0 && second.instanceId() == first.instanceId() + 1,
                "BOARD template insertion assigns stable monotonically increasing instance ids");
        check(first.nodeIds().size() == template.circuit.nodes.size(), "template insertion clones every real circuit node");
        check(board.wires.size() == template.circuit.wires.size() * 2, "template insertion clones real internal wires");
        for (int nodeId : first.nodeIds()) {
            EditorNode inserted = board.node(nodeId);
            check(inserted.templateInstanceId == first.instanceId(), "inserted node records template group membership");
            check(inserted.templateName.equals("LINK8"), "inserted node records template source name");
        }
        check(first.instanceId() != second.instanceId(), "separate inserts are separate replaceable template groups");
    }

    private static void identityMappingChecks() {
        BoardTemplateDefinition oldTemplate = passThroughTemplate("OLD", "bus-in", "bus-out", 8);
        BoardTemplateDefinition nextTemplate = passThroughTemplate("NEW", "bus-in", "bus-out", 8);
        socketByDirection(nextTemplate, PortDirection.INPUT).label = "RENAMED_INPUT";
        socketByDirection(nextTemplate, PortDirection.OUTPUT).label = "RENAMED_OUTPUT";
        nextTemplate.normalize();

        CircuitDocument board = new CircuitDocument();
        var inserted = BoardTemplateEngine.insert(board, oldTemplate, 100, 50);
        BoardTemplateReplacementPreview preview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), nextTemplate);
        check(preview.compatible(), "stable interface identity allows labels to change without breaking replacement");
        check(preview.mappings().size() == 2, "all old sockets map by stable identity");
        for (var mapping : preview.mappings()) {
            check(mapping.matchKind() == BoardTemplateReplacementPreview.MatchKind.INTERFACE_ID,
                    "stable interface identity has mapping priority");
        }
    }

    private static void signatureFallbackChecks() {
        BoardTemplateDefinition oldTemplate = passThroughTemplate("OLD_SIG", "old-input-id", "old-output-id", 4);
        BoardTemplateDefinition nextTemplate = passThroughTemplate("NEW_SIG", "new-input-id", "new-output-id", 4);
        CircuitDocument board = new CircuitDocument();
        var inserted = BoardTemplateEngine.insert(board, oldTemplate, 0, 0);
        BoardTemplateReplacementPreview preview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), nextTemplate);
        check(preview.compatible(), "unique name+direction+width signature can map a re-authored template");
        check(preview.mappings().size() == 2, "signature fallback maps both sockets");
        for (var mapping : preview.mappings()) {
            check(mapping.matchKind() == BoardTemplateReplacementPreview.MatchKind.NAME_DIRECTION_WIDTH,
                    "fallback mapping is explicitly reported in preview");
        }
    }

    private static void mismatchRejectionChecks() {
        BoardTemplateDefinition oldTemplate = passThroughTemplate("OLD_MISMATCH", "same-in", "same-out", 8);
        BoardTemplateDefinition widthChanged = passThroughTemplate("WIDTH_CHANGED", "same-in", "same-out", 16);
        CircuitDocument board = new CircuitDocument();
        var inserted = BoardTemplateEngine.insert(board, oldTemplate, 0, 0);
        BoardTemplateReplacementPreview widthPreview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), widthChanged);
        check(!widthPreview.compatible() && widthPreview.errors().stream().anyMatch(text -> text.contains("changed contract")),
                "same stable interface id with changed width is rejected rather than silently remapped");

        BoardTemplateDefinition missingOutput = singleSocketTemplate("MISSING", PortDirection.INPUT, "IN", "same-in", 8);
        EditorNode externalInput = board.addNode(NodeKind.INPUT, -100, 0);
        externalInput.width = 8;
        EditorNode externalOutput = board.addNode(NodeKind.OUTPUT, 250, 0);
        externalOutput.width = 8;
        connectExternal(board, inserted, oldTemplate, externalInput, externalOutput);
        BoardTemplateReplacementPreview missingPreview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), missingOutput);
        check(!missingPreview.compatible() && missingPreview.errors().stream().anyMatch(text -> text.contains("No compatible replacement")),
                "connected old socket cannot disappear during replacement");
    }

    private static void externalWireAndPcbPreservationChecks() {
        BoardTemplateDefinition oldTemplate = passThroughTemplate("PCB_OLD", "pcb-in", "pcb-out", 8);
        BoardTemplateDefinition nextTemplate = passThroughTemplate("PCB_NEW", "pcb-in", "pcb-out", 8);
        // Move replacement sockets so the operation really replaces layout, not just metadata.
        socketByDirection(nextTemplate, PortDirection.INPUT).x += 24;
        socketByDirection(nextTemplate, PortDirection.OUTPUT).x += 42;
        nextTemplate.normalize();

        CircuitDocument board = new CircuitDocument();
        EditorNode externalInput = board.addNode(NodeKind.INPUT, 0, 80);
        externalInput.width = 8;
        EditorNode externalOutput = board.addNode(NodeKind.OUTPUT, 420, 80);
        externalOutput.width = 8;
        var inserted = BoardTemplateEngine.insert(board, oldTemplate, 120, 80);
        connectExternal(board, inserted, oldTemplate, externalInput, externalOutput);

        EditorNode insertedInput = insertedNodeForSocket(board, inserted, socketByDirection(oldTemplate, PortDirection.INPUT));
        WireConnection incoming = findWire(board, externalInput.id, insertedInput.id);
        incoming.setRoutePoints(List.of(new RoutePoint(54, 80), new RoutePoint(90, 80)));
        incoming.setBranchStart(new RoutePoint(42, 80));
        incoming.setLayer(WireLayer.BACK);
        incoming.setViaRouteIndices(List.of(0));

        BoardTemplateReplacementPreview preview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), nextTemplate);
        check(preview.compatible() && preview.externalConnections() == 2,
                "replacement preview reports both preserved external connections");
        var replaced = BoardTemplateEngine.replace(board, inserted.instanceId(), nextTemplate);
        check(replaced.inserted().instanceId() != inserted.instanceId(), "replacement produces a fresh template instance group");

        EditorNode newInput = insertedNodeForSocket(board, replaced.inserted(), socketByDirection(nextTemplate, PortDirection.INPUT));
        WireConnection restoredIncoming = findWire(board, externalInput.id, newInput.id);
        check(restoredIncoming.layer() == WireLayer.BACK, "replacement preserves external wire PCB base layer");
        check(restoredIncoming.viaRouteIndices().equals(List.of(0)), "replacement preserves external wire vias");
        check(restoredIncoming.routePoints().equals(List.of(new RoutePoint(54, 80), new RoutePoint(90, 80))),
                "replacement preserves external wire route geometry");
        check(restoredIncoming.branchStart().equals(new RoutePoint(42, 80)),
                "replacement preserves external branch-start presentation metadata");

        CompiledCircuit compiled = CircuitCompiler.compile(board, name -> null);
        compiled.driveInputUnsigned(externalInput.id, 0x5AL);
        check(compiled.inputUnsigned(externalOutput.id, 0) == 0x5AL,
                "replaced template remains electrically connected through preserved sockets");
    }

    private static void boundaryBypassRejectionChecks() {
        BoardTemplateDefinition template = passThroughTemplate("BOUNDARY", "bound-in", "bound-out", 1);
        CircuitDocument board = new CircuitDocument();
        EditorNode external = board.addNode(NodeKind.INPUT, 0, 0);
        var inserted = BoardTemplateEngine.insert(board, template, 100, 0);
        EditorNode internalTarget = null;
        for (int nodeId : inserted.nodeIds()) {
            EditorNode candidate = board.node(nodeId);
            if (!candidate.isBoardSocket()) {
                internalTarget = candidate;
                break;
            }
        }
        if (internalTarget == null) {
            // Pass-through template has only sockets. Add a normal BUS member to construct an invalid bypass.
            internalTarget = board.addNode(NodeKind.BUS, 150, 60);
            internalTarget.templateInstanceId = inserted.instanceId();
            internalTarget.templateName = template.name;
        }
        board.connect(external.id, 0, internalTarget.id, 0);
        BoardTemplateReplacementPreview preview = BoardTemplateEngine.previewReplacement(board, inserted.instanceId(), template);
        check(!preview.compatible() && preview.errors().stream().anyMatch(text -> text.contains("bypasses sockets")),
                "replacement rejects external wiring that bypasses the template socket boundary");
    }

    private static void snapshotChecks() {
        CircuitDocument board = new CircuitDocument();
        EditorNode socket = board.addNode(NodeKind.BUS, 12, 18);
        socket.width = 12;
        socket.configureBoardSocket("ADDR", PortDirection.INPUT, 7);
        socket.interfaceId = "address-interface";
        socket.templateInstanceId = 4;
        socket.templateName = "MEMORY_BOARD";
        board.nextTemplateInstanceId = 9;
        board.normalize();

        CircuitDocument copy = EditorDocumentSnapshot.copy(board);
        check(EditorDocumentSnapshot.same(board, copy), "undo snapshot preserves all Phase 4 BOARD metadata");
        EditorNode copied = copy.node(socket.id);
        check(copied.isBoardSocket() && copied.interfaceId.equals("address-interface")
                        && copied.interfaceOrder == 7 && copied.templateInstanceId == 4
                        && copied.templateName.equals("MEMORY_BOARD"),
                "snapshot preserves socket identity/order and template instance membership");
        copied.interfaceId = "changed";
        check(board.node(socket.id).interfaceId.equals("address-interface"), "Phase 4 node metadata is deep copied");
    }

    private static BoardTemplateDefinition passThroughTemplate(String name, String inputId, String outputId, int width) {
        CircuitDocument circuit = new CircuitDocument();
        EditorNode input = circuit.addNode(NodeKind.BUS, 0, 0);
        input.width = width;
        input.configureBoardSocket("DATA_IN", PortDirection.INPUT, 0);
        input.interfaceId = inputId;
        EditorNode output = circuit.addNode(NodeKind.BUS, 120, 0);
        output.width = width;
        output.configureBoardSocket("DATA_OUT", PortDirection.OUTPUT, 1);
        output.interfaceId = outputId;
        circuit.connect(input.id, 0, output.id, 0);
        return new BoardTemplateDefinition(name, circuit);
    }

    private static BoardTemplateDefinition singleSocketTemplate(
            String name, PortDirection direction, String socketName, String interfaceId, int width
    ) {
        CircuitDocument circuit = new CircuitDocument();
        EditorNode socket = circuit.addNode(NodeKind.BUS, 0, 0);
        socket.width = width;
        socket.configureBoardSocket(socketName, direction, 0);
        socket.interfaceId = interfaceId;
        return new BoardTemplateDefinition(name, circuit);
    }

    private static EditorNode socketByDirection(BoardTemplateDefinition template, PortDirection direction) {
        for (EditorNode node : template.circuit.nodes) {
            if (node.isBoardSocket() && node.socketDirection == direction) return node;
        }
        throw new AssertionError("Missing " + direction + " socket in test template " + template.name);
    }

    private static void connectExternal(
            CircuitDocument board,
            BoardTemplateEngine.InsertResult inserted,
            BoardTemplateDefinition sourceTemplate,
            EditorNode externalInput,
            EditorNode externalOutput
    ) {
        EditorNode oldInputSocket = insertedNodeForSocket(board, inserted, socketByDirection(sourceTemplate, PortDirection.INPUT));
        EditorNode oldOutputSocket = insertedNodeForSocket(board, inserted, socketByDirection(sourceTemplate, PortDirection.OUTPUT));
        board.connect(externalInput.id, 0, oldInputSocket.id, 0);
        board.connect(oldOutputSocket.id, 0, externalOutput.id, 0);
    }

    private static EditorNode insertedNodeForSocket(
            CircuitDocument board,
            BoardTemplateEngine.InsertResult inserted,
            EditorNode templateSocket
    ) {
        Integer insertedId = inserted.sourceToInsertedNodeId().get(templateSocket.id);
        if (insertedId == null) throw new AssertionError("Template socket was not cloned");
        return board.node(insertedId);
    }

    private static WireConnection findWire(CircuitDocument board, int sourceId, int targetId) {
        for (WireConnection wire : board.wires) {
            if (wire.sourceNodeId() == sourceId && wire.targetNodeId() == targetId) return wire;
        }
        throw new AssertionError("Expected wire " + sourceId + " -> " + targetId);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
