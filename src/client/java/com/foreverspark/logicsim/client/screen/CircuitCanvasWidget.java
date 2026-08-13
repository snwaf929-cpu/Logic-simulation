package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.editor.runtime.NodePortKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Freeform cubic circuit canvas backed by the real NAND compiler/simulator. */
public final class CircuitCanvasWidget extends AbstractWidget {
    private static final double PORT_START_Y = 29.0;
    private static final double PORT_STEP = 15.0;
    private static final double NODE_GRID = 12.0;
    private static final double ROUTE_GRID = 6.0;
    private static final double EPSILON = 0.001;
    private static final int[] WIDTHS = {1, 2, 4, 8, 16, 32, 64};

    private final ClientChipLibrary chips;
    private final Consumer<String> status;
    private final Map<Integer, Long> inputStates = new HashMap<>();

    private CircuitDocument document;
    private CompiledCircuit runtime;
    private String compileError;

    private NodeKind placementKind;
    private String placementChipName;
    private Integer selectedNodeId;
    private WireConnection selectedWire;
    private NodePortKey wireStart;

    private Integer draggingNodeId;
    private double nodeDragDistance;
    private boolean nodeActuallyMoved;

    private boolean panning;
    private int panButton = -1;
    private double panX = 36.0;
    private double panY = 34.0;
    private double zoom = 1.0;

    private boolean wireEditMode;
    private Integer draggingRoutePointIndex;
    private Integer draggingSegmentIndex;

    public CircuitCanvasWidget(
            int x,
            int y,
            int width,
            int height,
            CircuitDocument document,
            ClientChipLibrary chips,
            Consumer<String> status
    ) {
        super(x, y, width, height, Component.literal("Circuit canvas"));
        this.document = document == null ? new CircuitDocument() : document;
        this.document.normalize();
        this.chips = chips;
        this.status = status;
        recompile();
    }

    public CircuitDocument document() {
        return document;
    }

    public String compileError() {
        return compileError;
    }

    public void setDocument(CircuitDocument document) {
        this.document = document == null ? new CircuitDocument() : document;
        this.document.normalize();
        inputStates.clear();
        clearSelection();
        cancelPlacement();
        wireEditMode = false;
        recompile();
        fitView();
    }

    public void newDocument() {
        setDocument(new CircuitDocument());
        resetView();
        status.accept("New empty circuit");
    }

    public void setPlacement(NodeKind kind) {
        placementKind = kind;
        placementChipName = null;
        wireStart = null;
        wireEditMode = false;
        status.accept("Place " + kind.name() + " — left-click the canvas. Right-drag to pan.");
    }

    public void setCustomChipPlacement(String chipName) {
        if (chipName == null || chipName.isBlank() || chips.find(chipName.trim()) == null) {
            status.accept("Saved chip not found: " + (chipName == null ? "" : chipName));
            return;
        }
        placementKind = NodeKind.CUSTOM_CHIP;
        placementChipName = chipName.trim();
        wireStart = null;
        wireEditMode = false;
        status.accept("Place " + placementChipName + " — left-click the canvas. F2 renames the library chip.");
    }

    public void cancelPlacement() {
        placementKind = null;
        placementChipName = null;
        wireStart = null;
    }

    /** Cancel editor modes without closing the screen. Returns true if something was cancelled. */
    public boolean cancelTransientMode() {
        if (placementKind != null || wireStart != null || wireEditMode) {
            cancelPlacement();
            wireEditMode = false;
            draggingRoutePointIndex = null;
            draggingSegmentIndex = null;
            status.accept("Cancelled current editor mode");
            return true;
        }
        return false;
    }

    public DeletionIntent deletionIntent() {
        if (selectedWire != null) {
            return new DeletionIntent(true, false, "Delete selected wire");
        }
        if (selectedNodeId != null) {
            EditorNode node = document.node(selectedNodeId);
            int connections = document.connectionCount(node.id);
            if (connections > 0) {
                return new DeletionIntent(
                        true,
                        true,
                        "Delete " + node.displayName() + " and " + connections + " connection" + (connections == 1 ? "" : "s") + "?"
                );
            }
            return new DeletionIntent(true, false, "Delete " + node.displayName());
        }
        return new DeletionIntent(false, false, "Nothing selected");
    }

    public void deleteSelectionConfirmed() {
        if (selectedWire != null) {
            document.removeWire(selectedWire);
            selectedWire = null;
            wireEditMode = false;
            status.accept("Wire deleted");
            return;
        }
        if (selectedNodeId != null) {
            int id = selectedNodeId;
            EditorNode node = document.node(id);
            int connections = document.connectionCount(id);
            document.removeNode(id);
            inputStates.remove(id);
            selectedNodeId = null;
            wireStart = null;
            wireEditMode = false;
            recompile();
            status.accept("Deleted " + node.displayName() + (connections > 0 ? " and " + connections + " connection" + (connections == 1 ? "" : "s") : ""));
            return;
        }
        status.accept("Nothing selected");
    }

    public void changeSelectedWidth(int direction) {
        if (selectedNodeId == null) {
            status.accept("Select an Input, Output, Splitter, or Merger first");
            return;
        }
        EditorNode node = document.node(selectedNodeId);
        if (node.kind == NodeKind.NAND || node.kind == NodeKind.CUSTOM_CHIP) {
            status.accept(node.kind + " width is fixed");
            return;
        }
        int index = 0;
        for (int i = 0; i < WIDTHS.length; i++) {
            if (WIDTHS[i] == node.width) {
                index = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(WIDTHS.length - 1, index + direction));
        if (WIDTHS[next] == node.width) return;

        int removedConnections = document.connectionCount(node.id);
        node.width = WIDTHS[next];
        document.removeWiresForNode(node.id);
        inputStates.put(node.id, 0L);
        wireStart = null;
        selectedWire = null;
        wireEditMode = false;
        recompile();
        status.accept(node.displayName() + " width = " + node.width + " bit" + (removedConnections > 0 ? "; incompatible attached wires were removed" : ""));
    }

    public void resetView() {
        panX = 36.0;
        panY = 34.0;
        zoom = 1.0;
    }

    public void fitView() {
        if (document.nodes.isEmpty()) {
            resetView();
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (EditorNode node : document.nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + nodeWidth(node));
            maxY = Math.max(maxY, node.y + nodeHeight(node));
        }

        double contentW = Math.max(40, maxX - minX);
        double contentH = Math.max(40, maxY - minY);
        double zx = Math.max(0.35, (width - 70.0) / contentW);
        double zy = Math.max(0.35, (height - 70.0) / contentH);
        zoom = clamp(Math.min(zx, zy), 0.35, 2.1);

        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        panX = width * 0.5 - centerX * zoom;
        panY = height * 0.5 - centerY * zoom;
    }

    public boolean toggleWireEditMode() {
        if (selectedWire == null) {
            status.accept("Select a wire first, then press E to edit its route");
            return false;
        }
        wireEditMode = !wireEditMode;
        if (wireEditMode) {
            ensureEditableRoute(selectedWire);
            status.accept("WIRE EDIT: drag square corners or interior segments; double-click a segment to add corners; E to finish");
        } else {
            draggingRoutePointIndex = null;
            draggingSegmentIndex = null;
            status.accept("Wire route edit finished");
        }
        return wireEditMode;
    }

    public boolean isWireEditMode() {
        return wireEditMode;
    }

    public void renameCustomChipReferences(String oldName, String newName) {
        boolean changed = false;
        for (EditorNode node : document.nodes) {
            if (node.kind == NodeKind.CUSTOM_CHIP && oldName.equals(node.chipName)) {
                node.chipName = newName;
                changed = true;
            }
        }
        if (changed) {
            recompile();
        }
        if (placementKind == NodeKind.CUSTOM_CHIP && oldName.equals(placementChipName)) {
            placementChipName = newName;
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = getX();
        int top = getY();
        graphics.fill(left, top, left + width, top + height, 0xF00D1014);
        graphics.outline(left, top, width, height, 0xFF343C46);
        drawGrid(graphics);

        for (WireConnection wire : document.wires) drawWire(graphics, wire);
        if (wireStart != null) drawWirePreview(graphics, mouseX, mouseY);
        for (EditorNode node : document.nodes) drawNode(graphics, node);

        if (wireEditMode && selectedWire != null) {
            drawRouteHandles(graphics, selectedWire);
        }

        String mode = placementKind == null
                ? wireStart == null ? wireEditMode ? "WIRE EDIT" : "SELECT" : "WIRE"
                : placementKind == NodeKind.CUSTOM_CHIP ? "PLACE " + placementChipName : "PLACE " + placementKind;
        graphics.text(font(), mode + "   " + Math.round(zoom * 100) + "%", left + 8, top + 8, wireEditMode ? 0xFF79C4FF : 0xFF84909E, false);
        graphics.text(font(), "RMB drag: pan   Del: delete   E: wire route   Ctrl+S: save", left + 8, top + height - 15, 0xFF5F6B78, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (!contains(mouseX, mouseY)) return;

        // Navigation is intentionally separated from left-click editing so panning cannot move a chip by accident.
        if (button == 1 || button == 2) {
            beginPan(button);
            return;
        }
        if (button != 0) return;

        if (placementKind != null) {
            double px = snap(worldX(mouseX), NODE_GRID);
            double py = snap(worldY(mouseY), NODE_GRID);
            EditorNode node = placementKind == NodeKind.CUSTOM_CHIP
                    ? document.addCustomChip(placementChipName, px, py)
                    : document.addNode(placementKind, px, py);
            selectedNodeId = node.id;
            selectedWire = null;
            wireEditMode = false;
            placementKind = null;
            placementChipName = null;
            recompile();
            status.accept("Placed " + node.displayName());
            return;
        }

        PortHit outputHit = outputPortAt(mouseX, mouseY);
        if (outputHit != null) {
            wireStart = new NodePortKey(outputHit.node.id, outputHit.port);
            selectedNodeId = outputHit.node.id;
            selectedWire = null;
            wireEditMode = false;
            status.accept("Wire " + outputHit.spec.width() + "-bit " + outputHit.node.displayName() + "." + outputHit.spec.name() + " -> choose a matching input");
            return;
        }

        PortHit inputHit = inputPortAt(mouseX, mouseY);
        if (inputHit != null && wireStart != null) {
            connectWire(inputHit);
            return;
        }

        if (wireEditMode && selectedWire != null) {
            Integer routePoint = routePointAt(selectedWire, mouseX, mouseY);
            if (routePoint != null) {
                draggingRoutePointIndex = routePoint;
                return;
            }
            SegmentHit segment = directSegmentAt(selectedWire, mouseX, mouseY);
            if (segment != null) {
                if (doubleClick) {
                    addDoglegCorners(selectedWire, segment.index, worldX(mouseX), worldY(mouseY));
                    return;
                }
                if (canDragSegment(selectedWire, segment.index)) {
                    draggingSegmentIndex = segment.index;
                    status.accept("Drag this wire segment perpendicular to reorganize the route");
                    return;
                }
            }
        }

        EditorNode node = nodeAt(mouseX, mouseY);
        if (node != null && node.kind == NodeKind.INPUT && inputToggleHit(node, mouseX, mouseY)) {
            selectedNodeId = node.id;
            selectedWire = null;
            wireEditMode = false;
            toggleInput(node);
            return;
        }

        if (node != null) {
            selectedNodeId = node.id;
            selectedWire = null;
            wireEditMode = false;
            draggingNodeId = node.id;
            nodeDragDistance = 0.0;
            nodeActuallyMoved = false;
            return;
        }

        WireConnection wire = wireAt(mouseX, mouseY);
        if (wire != null) {
            selectedWire = wire;
            selectedNodeId = null;
            wireEditMode = false;
            status.accept("Wire selected — Del/Backspace deletes; E edits route");
            return;
        }

        clearSelection();
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (panning && event.button() == panButton) {
            panX += dx;
            panY += dy;
            return;
        }

        if (event.button() != 0) return;

        if (draggingRoutePointIndex != null && selectedWire != null) {
            moveRoutePoint(selectedWire, draggingRoutePointIndex, worldX(event.x()), worldY(event.y()));
            return;
        }

        if (draggingSegmentIndex != null && selectedWire != null) {
            moveRouteSegment(selectedWire, draggingSegmentIndex, dx / zoom, dy / zoom);
            return;
        }

        if (draggingNodeId != null) {
            nodeDragDistance += Math.abs(dx) + Math.abs(dy);
            // Small click/hand jitter should select a node, not visibly move it.
            if (nodeDragDistance < 4.0) return;
            EditorNode node = document.node(draggingNodeId);
            node.x += dx / zoom;
            node.y += dy / zoom;
            nodeActuallyMoved = true;
            alignRoutesForNode(node.id);
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (panning && event.button() == panButton) {
            panning = false;
            panButton = -1;
        }

        if (event.button() == 0) {
            if (draggingNodeId != null && nodeActuallyMoved) {
                EditorNode node = document.node(draggingNodeId);
                node.x = snap(node.x, NODE_GRID);
                node.y = snap(node.y, NODE_GRID);
                alignRoutesForNode(node.id);
            }
            if (selectedWire != null && (draggingRoutePointIndex != null || draggingSegmentIndex != null)) {
                snapRoute(selectedWire);
            }
            draggingNodeId = null;
            draggingRoutePointIndex = null;
            draggingSegmentIndex = null;
            nodeDragDistance = 0.0;
            nodeActuallyMoved = false;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!contains(mouseX, mouseY)) return false;
        double worldXBefore = worldX(mouseX);
        double worldYBefore = worldY(mouseY);
        double amount = scrollY != 0.0 ? scrollY : scrollX;
        if (amount == 0) return true;
        zoom = clamp(zoom * (amount > 0 ? 1.12 : 1.0 / 1.12), 0.35, 2.5);
        panX = mouseX - getX() - worldXBefore * zoom;
        panY = mouseY - getY() - worldYBefore * zoom;
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }

    private void beginPan(int button) {
        panning = true;
        panButton = button;
        draggingNodeId = null;
        draggingRoutePointIndex = null;
        draggingSegmentIndex = null;
    }

    private void connectWire(PortHit target) {
        try {
            EditorNode source = document.node(wireStart.nodeId());
            List<PortSpec> sourcePorts = NodePorts.outputs(source, chips);
            if (wireStart.port() < 0 || wireStart.port() >= sourcePorts.size()) {
                status.accept("ERROR: Invalid source port");
                wireStart = null;
                return;
            }
            int sourceWidth = sourcePorts.get(wireStart.port()).width();
            if (sourceWidth != target.spec.width()) {
                status.accept("WIDTH MISMATCH: " + sourceWidth + "-bit -> " + target.spec.width() + "-bit. Use Splitter/Merger.");
                return;
            }
            document.connect(wireStart.nodeId(), wireStart.port(), target.node.id, target.port);
            selectedWire = document.wires.getLast();
            selectedNodeId = null;
            wireStart = null;
            wireEditMode = false;
            recompile();
            status.accept("Connected " + sourceWidth + "-bit " + (sourceWidth == 1 ? "wire" : "bus"));
        } catch (RuntimeException exception) {
            status.accept("ERROR: Cannot connect: " + exception.getMessage());
        }
    }

    private void toggleInput(EditorNode node) {
        long mask = node.width == 64 ? -1L : (1L << node.width) - 1L;
        long current = inputStates.getOrDefault(node.id, 0L);
        long next = current == 0L ? mask : 0L;
        inputStates.put(node.id, next);
        if (runtime != null) {
            try {
                runtime.driveInputUnsigned(node.id, next);
                status.accept(node.displayName() + " = " + formatUnsigned(next, node.width));
            } catch (RuntimeException exception) {
                status.accept("ERROR: Simulation: " + exception.getMessage());
            }
        }
    }

    private void recompile() {
        try {
            runtime = CircuitCompiler.compile(document, chips);
            for (EditorNode input : document.inputNodes()) {
                runtime.driveInputUnsigned(input.id, inputStates.getOrDefault(input.id, 0L));
            }
            compileError = null;
        } catch (RuntimeException exception) {
            runtime = null;
            compileError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            status.accept("ERROR: " + compileError);
        }
    }

    private void clearSelection() {
        selectedNodeId = null;
        selectedWire = null;
        draggingNodeId = null;
        wireStart = null;
        wireEditMode = false;
        draggingRoutePointIndex = null;
        draggingSegmentIndex = null;
    }

    private void drawGrid(GuiGraphicsExtractor graphics) {
        double spacing = 24.0 * zoom;
        if (spacing < 9.0) spacing *= 4.0;
        double startX = getX() + mod(panX, spacing);
        for (double x = startX; x < getX() + width; x += spacing) {
            graphics.fill((int) x, getY(), (int) x + 1, getY() + height, 0xFF171B20);
        }
        double startY = getY() + mod(panY, spacing);
        for (double y = startY; y < getY() + height; y += spacing) {
            graphics.fill(getX(), (int) y, getX() + width, (int) y + 1, 0xFF171B20);
        }
    }

    private void drawNode(GuiGraphicsExtractor graphics, EditorNode node) {
        List<PortSpec> inputs = safeInputs(node);
        List<PortSpec> outputs = safeOutputs(node);
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(34, (int) Math.round(nodeWidth(node) * zoom));
        int h = Math.max(24, (int) Math.round(nodeHeight(node) * zoom));
        if (x > getX() + width || y > getY() + height || x + w < getX() || y + h < getY()) return;

        int accent = nodeAccent(node);
        int border = selectedNodeId != null && selectedNodeId == node.id ? 0xFFFFFFFF : darken(accent, 0.82);
        graphics.fill(x, y, x + w, y + h, 0xF01B2026);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x, y, x + w, y + Math.max(4, (int) Math.round(5 * zoom)), accent);

        String title = compactTitle(node);
        graphics.text(font(), truncate(title, node.kind == NodeKind.CUSTOM_CHIP ? 20 : 11), x + 5, y + 8, 0xFFF1F4F7, false);

        if (node.kind == NodeKind.INPUT) {
            drawInputSwitch(graphics, node, x, y, w, h);
        } else if (node.kind == NodeKind.OUTPUT) {
            drawValueBox(graphics, valueForNode(node), x + 5, y + h - Math.max(18, (int) (19 * zoom)), Math.max(30, w - 10), Math.max(13, (int) (14 * zoom)));
        } else if (node.kind == NodeKind.NAND) {
            LogicValue[] value = valueForNode(node);
            graphics.text(font(), formatValues(value), x + 5, y + h - 14, valueColor(value), false);
        } else if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) {
            graphics.text(font(), node.width + " bit", x + 5, y + 21, 0xFF8DA0B5, false);
        } else {
            graphics.text(font(), formatValues(valueForNode(node)), x + 5, y + 21, valueColor(valueForNode(node)), false);
        }

        for (int port = 0; port < inputs.size(); port++) {
            Point point = inputPortPoint(node, port);
            int color = inputPortDisplayColor(node, port, inputs.get(port));
            drawPort(graphics, point, color, wireStart != null);
            if (zoom >= 0.78 && shouldShowPortLabel(node, inputs.size())) {
                graphics.text(font(), truncate(inputs.get(port).name(), 7), screenX(point.x) + 7, screenY(point.y) - 4, 0xFFAAB4C0, false);
            }
        }
        for (int port = 0; port < outputs.size(); port++) {
            Point point = outputPortPoint(node, port);
            drawPort(graphics, point, portColor(node, port, false), false);
            if (zoom >= 0.78 && shouldShowPortLabel(node, outputs.size())) {
                String label = truncate(outputs.get(port).name(), 7);
                graphics.text(font(), label, screenX(point.x) - 7 - font().width(label), screenY(point.y) - 4, 0xFFAAB4C0, false);
            }
        }
    }

    private int inputPortDisplayColor(EditorNode node, int port, PortSpec spec) {
        if (wireStart == null) return portColor(node, port, true);
        int sourceWidth = wireStartWidth();
        if (sourceWidth < 0) return 0xFF777777;
        return sourceWidth == spec.width() ? 0xFF55D96B : 0xFFE05252;
    }

    private void drawInputSwitch(GuiGraphicsExtractor graphics, EditorNode node, int x, int y, int w, int h) {
        long value = inputStates.getOrDefault(node.id, 0L);
        int boxX = x + 5;
        int boxY = y + h - Math.max(18, (int) Math.round(19 * zoom));
        int boxW = Math.max(30, w - 10);
        int boxH = Math.max(13, (int) Math.round(14 * zoom));
        int col = value == 0L ? 0xFF7D3539 : 0xFF2F8B48;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, col);
        graphics.outline(boxX, boxY, boxW, boxH, value == 0L ? 0xFFB85A5E : 0xFF58C56F);
        String text = node.width == 1 ? (value == 0L ? "OFF 0" : "ON 1") : formatUnsigned(value, node.width);
        graphics.text(font(), text, boxX + 4, boxY + 3, 0xFFFFFFFF, false);
    }

    private void drawValueBox(GuiGraphicsExtractor graphics, LogicValue[] values, int x, int y, int w, int h) {
        int col = darken(valueColor(values), 0.55);
        graphics.fill(x, y, x + w, y + h, col);
        graphics.outline(x, y, w, h, valueColor(values));
        graphics.text(font(), formatValues(values), x + 4, y + 3, 0xFFFFFFFF, false);
    }

    private boolean shouldShowPortLabel(EditorNode node, int portCount) {
        return portCount <= 16 && node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT;
    }

    private void drawPort(GuiGraphicsExtractor graphics, Point point, int color, boolean wiringTarget) {
        int x = screenX(point.x);
        int y = screenY(point.y);
        int r = Math.max(3, (int) Math.round((wiringTarget ? 4.2 : 3.5) * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF090B0D);
    }

    private void drawWire(GuiGraphicsExtractor graphics, WireConnection wire) {
        int color = selectedWire != null && selectedWire.equals(wire) ? 0xFFFFFFFF : wireColor(wire);
        int thickness = selectedWire != null && selectedWire.equals(wire) ? 3 : 2;
        for (Segment segment : expandedSegments(wire)) {
            drawWorldSegment(graphics, segment.a, segment.b, thickness, color);
        }

        EditorNode source;
        try {
            source = document.node(wire.sourceNodeId());
        } catch (RuntimeException ignored) {
            return;
        }
        List<PortSpec> outputs = safeOutputs(source);
        if (wire.sourcePort() >= outputs.size()) return;
        int busWidth = outputs.get(wire.sourcePort()).width();
        if (busWidth > 1) {
            Point labelPoint = routeLabelPoint(wire);
            int lx = screenX(labelPoint.x);
            int ly = screenY(labelPoint.y);
            graphics.fill(lx - 2, ly - 7, lx + 25, ly + 5, 0xEE11161C);
            graphics.text(font(), "[" + busWidth + "]", lx, ly - 5, 0xFF8DB7FF, false);
        }
    }

    private void drawWirePreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        try {
            EditorNode source = document.node(wireStart.nodeId());
            Point start = outputPortPoint(source, wireStart.port());
            int color = 0xFF6CA9FF;
            PortHit target = inputPortAt(mouseX, mouseY);
            if (target != null) {
                color = target.spec.width() == wireStartWidth() ? 0xFF55D96B : 0xFFE05252;
            }
            int sx = screenX(start.x);
            int sy = screenY(start.y);
            int mx = (sx + mouseX) / 2;
            drawHorizontal(graphics, sx, mx, sy, 2, color);
            drawVertical(graphics, mx, sy, mouseY, 2, color);
            drawHorizontal(graphics, mx, mouseX, mouseY, 2, color);
            graphics.outline(sx - 5, sy - 5, 11, 11, 0xFFFFFFFF);
        } catch (RuntimeException ignored) {
        }
    }

    private void drawRouteHandles(GuiGraphicsExtractor graphics, WireConnection wire) {
        List<RoutePoint> route = wire.routePoints();
        for (int i = 0; i < route.size(); i++) {
            RoutePoint point = route.get(i);
            int x = screenX(point.x());
            int y = screenY(point.y());
            int r = 4;
            graphics.fill(x - r, y - r, x + r + 1, y + r + 1, 0xFF12171D);
            graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF79C4FF);
        }
    }

    private void drawWorldSegment(GuiGraphicsExtractor graphics, Point a, Point b, int thickness, int color) {
        int x1 = screenX(a.x);
        int y1 = screenY(a.y);
        int x2 = screenX(b.x);
        int y2 = screenY(b.y);
        if (Math.abs(a.y - b.y) < EPSILON) {
            drawHorizontal(graphics, x1, x2, y1, thickness, color);
        } else if (Math.abs(a.x - b.x) < EPSILON) {
            drawVertical(graphics, x1, y1, y2, thickness, color);
        } else {
            drawHorizontal(graphics, x1, x2, y1, thickness, color);
            drawVertical(graphics, x2, y1, y2, thickness, color);
        }
    }

    private int nodeAccent(EditorNode node) {
        return switch (node.kind) {
            case INPUT -> 0xFF4C86D9;
            case OUTPUT -> 0xFF7B68D9;
            case NAND -> 0xFF65717F;
            case SPLITTER, MERGER -> 0xFF4FA6A0;
            case CUSTOM_CHIP -> chips.chipColor(node.chipName);
        };
    }

    private String compactTitle(EditorNode node) {
        if (node.kind == NodeKind.INPUT) return node.label == null || node.label.isBlank() ? "INPUT" : node.label;
        if (node.kind == NodeKind.OUTPUT) return node.label == null || node.label.isBlank() ? "OUTPUT" : node.label;
        if (node.kind == NodeKind.NAND) return "NAND";
        return node.displayName();
    }

    private int portColor(EditorNode node, int port, boolean input) {
        if (runtime == null) return 0xFF777777;
        try {
            LogicValue[] values = input ? runtime.inputValues(node.id, port) : runtime.outputValues(node.id, port);
            return valueColor(values);
        } catch (RuntimeException ignored) {
            return 0xFF777777;
        }
    }

    private int wireColor(WireConnection wire) {
        if (runtime == null) return 0xFF6B7280;
        try {
            return valueColor(runtime.outputValues(wire.sourceNodeId(), wire.sourcePort()));
        } catch (RuntimeException ignored) {
            return 0xFF6B7280;
        }
    }

    private LogicValue[] valueForNode(EditorNode node) {
        if (runtime == null) return new LogicValue[]{LogicValue.UNKNOWN};
        try {
            return switch (node.kind) {
                case INPUT, NAND, MERGER -> runtime.outputValues(node.id, 0);
                case OUTPUT, SPLITTER -> runtime.inputValues(node.id, 0);
                case CUSTOM_CHIP -> safeOutputs(node).isEmpty()
                        ? (safeInputs(node).isEmpty() ? new LogicValue[]{LogicValue.UNKNOWN} : runtime.inputValues(node.id, 0))
                        : runtime.outputValues(node.id, 0);
            };
        } catch (RuntimeException ignored) {
            return new LogicValue[]{LogicValue.UNKNOWN};
        }
    }

    private boolean inputToggleHit(EditorNode node, double mouseX, double mouseY) {
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(34, (int) Math.round(nodeWidth(node) * zoom));
        int h = Math.max(24, (int) Math.round(nodeHeight(node) * zoom));
        int boxY = y + h - Math.max(18, (int) Math.round(19 * zoom));
        return mouseX >= x + 4 && mouseX <= x + w - 4 && mouseY >= boxY - 2 && mouseY <= y + h - 2;
    }

    private PortHit outputPortAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeOutputs(node);
            for (int port = 0; port < ports.size(); port++) {
                if (near(mouseX, mouseY, outputPortPoint(node, port), 8.0)) return new PortHit(node, port, ports.get(port));
            }
        }
        return null;
    }

    private PortHit inputPortAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeInputs(node);
            for (int port = 0; port < ports.size(); port++) {
                if (near(mouseX, mouseY, inputPortPoint(node, port), 9.0)) return new PortHit(node, port, ports.get(port));
            }
        }
        return null;
    }

    private EditorNode nodeAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            double x = screenX(node.x);
            double y = screenY(node.y);
            double w = nodeWidth(node) * zoom;
            double h = nodeHeight(node) * zoom;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) return node;
        }
        return null;
    }

    private WireConnection wireAt(double mouseX, double mouseY) {
        for (int i = document.wires.size() - 1; i >= 0; i--) {
            WireConnection wire = document.wires.get(i);
            for (Segment segment : expandedSegments(wire)) {
                if (distanceToSegment(mouseX, mouseY,
                        screenX(segment.a.x), screenY(segment.a.y),
                        screenX(segment.b.x), screenY(segment.b.y)) <= 6.0) {
                    return wire;
                }
            }
        }
        return null;
    }

    private Integer routePointAt(WireConnection wire, double mouseX, double mouseY) {
        List<RoutePoint> route = wire.routePoints();
        for (int i = route.size() - 1; i >= 0; i--) {
            RoutePoint point = route.get(i);
            double dx = mouseX - screenX(point.x());
            double dy = mouseY - screenY(point.y());
            if (dx * dx + dy * dy <= 64.0) return i;
        }
        return null;
    }

    private SegmentHit directSegmentAt(WireConnection wire, double mouseX, double mouseY) {
        List<Point> points = directWirePoints(wire);
        for (int i = 0; i < points.size() - 1; i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (Math.abs(a.x - b.x) >= EPSILON && Math.abs(a.y - b.y) >= EPSILON) continue;
            if (distanceToSegment(mouseX, mouseY, screenX(a.x), screenY(a.y), screenX(b.x), screenY(b.y)) <= 7.0) {
                return new SegmentHit(i, a, b);
            }
        }
        return null;
    }

    private void ensureEditableRoute(WireConnection wire) {
        if (!wire.routePoints().isEmpty()) return;
        Point start = wireStartPoint(wire);
        Point end = wireEndPoint(wire);
        double midX = snap((start.x + end.x) * 0.5, ROUTE_GRID);
        wire.routePoints().add(new RoutePoint(midX, start.y));
        wire.routePoints().add(new RoutePoint(midX, end.y));
    }

    private boolean canDragSegment(WireConnection wire, int directSegmentIndex) {
        int routeCount = wire.routePoints().size();
        return directSegmentIndex >= 1 && directSegmentIndex < routeCount;
    }

    private void moveRouteSegment(WireConnection wire, int directSegmentIndex, double dx, double dy) {
        if (!canDragSegment(wire, directSegmentIndex)) return;
        List<RoutePoint> route = wire.routePoints();
        int firstRouteIndex = directSegmentIndex - 1;
        int secondRouteIndex = directSegmentIndex;
        RoutePoint a = route.get(firstRouteIndex);
        RoutePoint b = route.get(secondRouteIndex);
        if (Math.abs(a.y() - b.y()) < EPSILON) {
            route.set(firstRouteIndex, new RoutePoint(a.x(), a.y() + dy));
            route.set(secondRouteIndex, new RoutePoint(b.x(), b.y() + dy));
        } else if (Math.abs(a.x() - b.x()) < EPSILON) {
            route.set(firstRouteIndex, new RoutePoint(a.x() + dx, a.y()));
            route.set(secondRouteIndex, new RoutePoint(b.x() + dx, b.y()));
        }
    }

    private void moveRoutePoint(WireConnection wire, int routeIndex, double worldX, double worldY) {
        List<RoutePoint> route = wire.routePoints();
        if (routeIndex < 0 || routeIndex >= route.size()) return;

        List<Point> full = directWirePoints(wire);
        int fullIndex = routeIndex + 1;
        Point prev = full.get(fullIndex - 1);
        Point current = full.get(fullIndex);
        Point next = full.get(fullIndex + 1);

        boolean prevHorizontal = Math.abs(prev.y - current.y) < EPSILON;
        boolean nextHorizontal = Math.abs(next.y - current.y) < EPSILON;
        double x = snap(worldX, ROUTE_GRID);
        double y = snap(worldY, ROUTE_GRID);

        if (prevHorizontal && !nextHorizontal) {
            if (routeIndex > 0) {
                RoutePoint previousRoute = route.get(routeIndex - 1);
                route.set(routeIndex - 1, new RoutePoint(previousRoute.x(), y));
            } else {
                y = prev.y;
            }
            if (routeIndex + 1 < route.size()) {
                RoutePoint nextRoute = route.get(routeIndex + 1);
                route.set(routeIndex + 1, new RoutePoint(x, nextRoute.y()));
            } else {
                x = next.x;
            }
            route.set(routeIndex, new RoutePoint(x, y));
            return;
        }

        if (!prevHorizontal && nextHorizontal) {
            if (routeIndex > 0) {
                RoutePoint previousRoute = route.get(routeIndex - 1);
                route.set(routeIndex - 1, new RoutePoint(x, previousRoute.y()));
            } else {
                x = prev.x;
            }
            if (routeIndex + 1 < route.size()) {
                RoutePoint nextRoute = route.get(routeIndex + 1);
                route.set(routeIndex + 1, new RoutePoint(nextRoute.x(), y));
            } else {
                y = next.y;
            }
            route.set(routeIndex, new RoutePoint(x, y));
            return;
        }

        if (prevHorizontal) {
            route.set(routeIndex, new RoutePoint(x, current.y));
        } else {
            route.set(routeIndex, new RoutePoint(current.x, y));
        }
    }

    private void addDoglegCorners(WireConnection wire, int directSegmentIndex, double worldX, double worldY) {
        ensureEditableRoute(wire);
        List<Point> full = directWirePoints(wire);
        if (directSegmentIndex < 0 || directSegmentIndex >= full.size() - 1) return;
        Point a = full.get(directSegmentIndex);
        Point b = full.get(directSegmentIndex + 1);
        int insertionIndex = Math.max(0, Math.min(wire.routePoints().size(), directSegmentIndex));
        double half = 8.0;

        if (Math.abs(a.y - b.y) < EPSILON) {
            double min = Math.min(a.x, b.x) + 3.0;
            double max = Math.max(a.x, b.x) - 3.0;
            if (max <= min) return;
            double center = clamp(worldX, min, max);
            double dir = b.x >= a.x ? 1.0 : -1.0;
            double p1x = clamp(center - half * dir, Math.min(a.x, b.x), Math.max(a.x, b.x));
            double p2x = clamp(center + half * dir, Math.min(a.x, b.x), Math.max(a.x, b.x));
            wire.routePoints().add(insertionIndex, new RoutePoint(p1x, a.y));
            wire.routePoints().add(insertionIndex + 1, new RoutePoint(p2x, a.y));
        } else if (Math.abs(a.x - b.x) < EPSILON) {
            double min = Math.min(a.y, b.y) + 3.0;
            double max = Math.max(a.y, b.y) - 3.0;
            if (max <= min) return;
            double center = clamp(worldY, min, max);
            double dir = b.y >= a.y ? 1.0 : -1.0;
            double p1y = clamp(center - half * dir, Math.min(a.y, b.y), Math.max(a.y, b.y));
            double p2y = clamp(center + half * dir, Math.min(a.y, b.y), Math.max(a.y, b.y));
            wire.routePoints().add(insertionIndex, new RoutePoint(a.x, p1y));
            wire.routePoints().add(insertionIndex + 1, new RoutePoint(a.x, p2y));
        }
        snapRoute(wire);
        status.accept("Added route corners — drag the new interior segment to make a clean detour");
    }

    private void snapRoute(WireConnection wire) {
        List<RoutePoint> route = wire.routePoints();
        for (int i = 0; i < route.size(); i++) {
            RoutePoint point = route.get(i);
            route.set(i, new RoutePoint(snap(point.x(), ROUTE_GRID), snap(point.y(), ROUTE_GRID)));
        }
        alignRouteEnds(wire);
    }

    private void alignRoutesForNode(int nodeId) {
        for (WireConnection wire : document.wires) {
            if (wire.routePoints().isEmpty()) continue;
            if (wire.sourceNodeId() == nodeId || wire.targetNodeId() == nodeId) {
                alignRouteEnds(wire);
            }
        }
    }

    private void alignRouteEnds(WireConnection wire) {
        List<RoutePoint> route = wire.routePoints();
        if (route.isEmpty()) return;
        try {
            Point start = wireStartPoint(wire);
            Point end = wireEndPoint(wire);
            RoutePoint first = route.getFirst();
            route.set(0, new RoutePoint(first.x(), start.y));
            RoutePoint last = route.getLast();
            route.set(route.size() - 1, new RoutePoint(last.x(), end.y));
        } catch (RuntimeException ignored) {
        }
    }

    private List<Point> directWirePoints(WireConnection wire) {
        List<Point> result = new ArrayList<>();
        result.add(wireStartPoint(wire));
        for (RoutePoint routePoint : wire.routePoints()) {
            result.add(new Point(routePoint.x(), routePoint.y()));
        }
        result.add(wireEndPoint(wire));
        return result;
    }

    private List<Segment> expandedSegments(WireConnection wire) {
        List<Point> points;
        try {
            if (wire.routePoints().isEmpty()) {
                Point start = wireStartPoint(wire);
                Point end = wireEndPoint(wire);
                double midX = (start.x + end.x) * 0.5;
                points = List.of(start, new Point(midX, start.y), new Point(midX, end.y), end);
            } else {
                points = directWirePoints(wire);
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }

        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (Math.abs(a.x - b.x) < EPSILON || Math.abs(a.y - b.y) < EPSILON) {
                segments.add(new Segment(a, b));
            } else {
                Point corner = new Point(b.x, a.y);
                segments.add(new Segment(a, corner));
                segments.add(new Segment(corner, b));
            }
        }
        return segments;
    }

    private Point routeLabelPoint(WireConnection wire) {
        List<Segment> segments = expandedSegments(wire);
        if (segments.isEmpty()) return new Point(0, 0);
        Segment segment = segments.get(segments.size() / 2);
        return new Point((segment.a.x + segment.b.x) * 0.5, (segment.a.y + segment.b.y) * 0.5);
    }

    private Point wireStartPoint(WireConnection wire) {
        EditorNode source = document.node(wire.sourceNodeId());
        return outputPortPoint(source, wire.sourcePort());
    }

    private Point wireEndPoint(WireConnection wire) {
        EditorNode target = document.node(wire.targetNodeId());
        return inputPortPoint(target, wire.targetPort());
    }

    private int wireStartWidth() {
        if (wireStart == null) return -1;
        try {
            EditorNode source = document.node(wireStart.nodeId());
            List<PortSpec> outputs = safeOutputs(source);
            return wireStart.port() >= 0 && wireStart.port() < outputs.size() ? outputs.get(wireStart.port()).width() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private List<PortSpec> safeInputs(EditorNode node) {
        try {
            return NodePorts.inputs(node, chips);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<PortSpec> safeOutputs(EditorNode node) {
        try {
            return NodePorts.outputs(node, chips);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private ChipVisualSettings visual(EditorNode node) {
        return node.kind == NodeKind.CUSTOM_CHIP ? chips.chipVisual(node.chipName) : new ChipVisualSettings();
    }

    private double nodeWidth(EditorNode node) {
        return switch (node.kind) {
            case INPUT, OUTPUT -> 72.0;
            case NAND -> 78.0;
            case SPLITTER, MERGER -> 102.0;
            case CUSTOM_CHIP -> visual(node).width;
        };
    }

    private double nodeHeight(EditorNode node) {
        if (node.kind == NodeKind.INPUT || node.kind == NodeKind.OUTPUT) return 43.0;
        int count = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        if (node.kind == NodeKind.NAND) return 55.0;
        if (node.kind == NodeKind.CUSTOM_CHIP) {
            ChipVisualSettings visual = visual(node);
            return Math.max(visual.minHeight, PORT_START_Y + Math.max(1, count) * visual.portSpacing + 8.0);
        }
        return Math.max(48.0, PORT_START_Y + Math.max(1, count) * PORT_STEP + 8.0);
    }

    private double portStep(EditorNode node) {
        return node.kind == NodeKind.CUSTOM_CHIP ? visual(node).portSpacing : PORT_STEP;
    }

    private Point inputPortPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case OUTPUT, SPLITTER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + PORT_START_Y + port * portStep(node);
        };
        return new Point(node.x, y);
    }

    private Point outputPortPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case INPUT, NAND, MERGER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + PORT_START_Y + port * portStep(node);
        };
        return new Point(node.x + nodeWidth(node), y);
    }

    private boolean near(double screenMouseX, double screenMouseY, Point worldPoint, double radius) {
        double dx = screenMouseX - screenX(worldPoint.x);
        double dy = screenMouseY - screenY(worldPoint.y);
        return dx * dx + dy * dy <= radius * radius;
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    private int screenX(double worldX) {
        return (int) Math.round(getX() + panX + worldX * zoom);
    }

    private int screenY(double worldY) {
        return (int) Math.round(getY() + panY + worldY * zoom);
    }

    private double worldX(double screenX) {
        return (screenX - getX() - panX) / zoom;
    }

    private double worldY(double screenY) {
        return (screenY - getY() - panY) / zoom;
    }

    private net.minecraft.client.gui.Font font() {
        return Minecraft.getInstance().font;
    }

    private static int valueColor(LogicValue[] values) {
        if (values == null || values.length == 0) return 0xFF777777;
        boolean unknown = false;
        boolean high = false;
        boolean low = false;
        for (LogicValue value : values) {
            if (value == LogicValue.UNKNOWN) unknown = true;
            if (value == LogicValue.HIGH) high = true;
            if (value == LogicValue.LOW) low = true;
        }
        if (unknown) return 0xFFFFC857;
        if (high && low) return 0xFF5AA9FF;
        if (high) return 0xFF55D96B;
        return 0xFFE05252;
    }

    private static int darken(int color, double factor) {
        int r = (int) (((color >>> 16) & 0xFF) * factor);
        int g = (int) (((color >>> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String formatValues(LogicValue[] values) {
        if (values == null || values.length == 0) return "-";
        for (LogicValue value : values) {
            if (value == LogicValue.UNKNOWN) return values.length == 1 ? "X" : "X[" + values.length + "]";
        }
        long numeric = 0L;
        for (int bit = 0; bit < values.length; bit++) {
            if (values[bit] == LogicValue.HIGH) numeric |= (1L << bit);
        }
        return values.length == 1 ? Long.toString(numeric) : formatUnsigned(numeric, values.length);
    }

    private static String formatUnsigned(long value, int width) {
        int digits = Math.max(1, (width + 3) / 4);
        String raw = Long.toUnsignedString(value, 16).toUpperCase();
        if (raw.length() < digits) raw = "0".repeat(digits - raw.length()) + raw;
        if (raw.length() > digits) raw = raw.substring(raw.length() - digits);
        return "0x" + raw;
    }

    private static void drawHorizontal(GuiGraphicsExtractor graphics, int x1, int x2, int y, int thickness, int color) {
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        graphics.fill(left, y - thickness / 2, right + 1, y + (thickness + 1) / 2, color);
    }

    private static void drawVertical(GuiGraphicsExtractor graphics, int x, int y1, int y2, int thickness, int color) {
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        graphics.fill(x - thickness / 2, top, x + (thickness + 1) / 2, bottom + 1, color);
    }

    private static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0.0 && dy == 0.0) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = clamp(t, 0.0, 1.0);
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double snap(double value, double grid) {
        return Math.round(value / grid) * grid;
    }

    private static double mod(double value, double divisor) {
        double result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    public record DeletionIntent(boolean hasSelection, boolean confirmationRequired, String description) {
    }

    private record Point(double x, double y) {
    }

    private record PortHit(EditorNode node, int port, PortSpec spec) {
    }

    private record Segment(Point a, Point b) {
    }

    private record SegmentHit(int index, Point a, Point b) {
    }
}
