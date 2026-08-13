package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import com.foreverspark.logicsim.editor.runtime.NodePortKey;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Freeform circuit canvas. The editor is only a view/controller over the NAND compiler. */
public final class CircuitCanvasWidget extends AbstractWidget {
    private static final double NODE_WIDTH = 126.0;
    private static final double PORT_START_Y = 31.0;
    private static final double PORT_STEP = 17.0;
    private static final int[] WIDTHS = {1, 2, 4, 8, 16, 32, 64};

    private final ChipLookup chips;
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
    private boolean panning;

    private double panX = 36.0;
    private double panY = 34.0;
    private double zoom = 1.0;

    public CircuitCanvasWidget(
            int x,
            int y,
            int width,
            int height,
            CircuitDocument document,
            ChipLookup chips,
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
        recompile();
        resetView();
    }

    public void newDocument() {
        setDocument(new CircuitDocument());
        status.accept("New empty circuit");
    }

    public void setPlacement(NodeKind kind) {
        placementKind = kind;
        placementChipName = null;
        wireStart = null;
        status.accept("Place " + kind.name());
    }

    public void setCustomChipPlacement(String chipName) {
        if (chipName == null || chipName.isBlank() || chips.find(chipName.trim()) == null) {
            status.accept("Saved chip not found: " + (chipName == null ? "" : chipName));
            return;
        }
        placementKind = NodeKind.CUSTOM_CHIP;
        placementChipName = chipName.trim();
        wireStart = null;
        status.accept("Place custom chip: " + placementChipName);
    }

    public void deleteSelection() {
        if (selectedWire != null) {
            document.removeWire(selectedWire);
            selectedWire = null;
            recompile();
            status.accept("Wire deleted");
            return;
        }
        if (selectedNodeId != null) {
            int id = selectedNodeId;
            document.removeNode(id);
            inputStates.remove(id);
            selectedNodeId = null;
            wireStart = null;
            recompile();
            status.accept("Node " + id + " deleted");
        }
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
        if (WIDTHS[next] == node.width) {
            return;
        }
        node.width = WIDTHS[next];
        document.removeWiresForNode(node.id);
        inputStates.put(node.id, 0L);
        wireStart = null;
        selectedWire = null;
        recompile();
        status.accept(node.displayName() + " width = " + node.width + " bit (attached wires cleared)");
    }

    public void resetView() {
        panX = 36.0;
        panY = 34.0;
        zoom = 1.0;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = getX();
        int top = getY();
        graphics.fill(left, top, left + width, top + height, 0xF0101114);
        graphics.outline(left, top, width, height, 0xFF3F444D);
        drawGrid(graphics);

        for (WireConnection wire : document.wires) {
            drawWire(graphics, wire);
        }
        if (wireStart != null) {
            Point start = outputPortPoint(document.node(wireStart.nodeId()), wireStart.port());
            int sx = screenX(start.x);
            int sy = screenY(start.y);
            graphics.fill(sx - 5, sy - 5, sx + 6, sy + 6, 0xFFFFFFFF);
        }
        for (EditorNode node : document.nodes) {
            drawNode(graphics, node);
        }

        String mode = placementKind == null ? "SELECT" : "PLACE " + placementKind;
        graphics.text(nullSafeFont(), mode + "  |  zoom " + Math.round(zoom * 100) + "%", left + 7, top + 7, 0xFF9AA4B2, true);
        if (compileError != null) {
            graphics.text(nullSafeFont(), "ERROR: " + truncate(compileError, 72), left + 7, top + height - 15, 0xFFFF6B6B, true);
        } else {
            graphics.text(nullSafeFont(), "Right-click INPUT = toggle 0/all-1 • middle-drag = pan • wheel = zoom", left + 7, top + height - 15, 0xFF7F8A99, true);
        }
    }

    private net.minecraft.client.gui.Font nullSafeFont() {
        return net.minecraft.client.Minecraft.getInstance().font;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (button == 2) {
            panning = true;
            return;
        }

        if (button == 1) {
            WireConnection wire = wireAt(mouseX, mouseY);
            if (wire != null) {
                document.removeWire(wire);
                selectedWire = null;
                recompile();
                status.accept("Wire deleted");
                return;
            }
            EditorNode node = nodeAt(mouseX, mouseY);
            if (node != null && node.kind == NodeKind.INPUT) {
                toggleInput(node);
                return;
            }
            wireStart = null;
            placementKind = null;
            status.accept("Wire/placement cancelled");
            return;
        }

        if (button != 0) {
            return;
        }

        if (placementKind != null) {
            double wx = worldX(mouseX);
            double wy = worldY(mouseY);
            EditorNode node;
            if (placementKind == NodeKind.CUSTOM_CHIP) {
                node = document.addCustomChip(placementChipName, wx, wy);
            } else {
                node = document.addNode(placementKind, wx, wy);
            }
            selectedNodeId = node.id;
            selectedWire = null;
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
            status.accept("Wire started from " + outputHit.node.displayName() + "." + outputHit.spec.name());
            return;
        }

        PortHit inputHit = inputPortAt(mouseX, mouseY);
        if (inputHit != null && wireStart != null) {
            connectWire(inputHit);
            return;
        }

        EditorNode node = nodeAt(mouseX, mouseY);
        if (node != null) {
            selectedNodeId = node.id;
            selectedWire = null;
            draggingNodeId = node.id;
            return;
        }

        WireConnection wire = wireAt(mouseX, mouseY);
        if (wire != null) {
            selectedWire = wire;
            selectedNodeId = null;
            return;
        }

        clearSelection();
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 2 && panning) {
            panX += dx;
            panY += dy;
            return;
        }
        if (event.button() == 0 && draggingNodeId != null) {
            EditorNode node = document.node(draggingNodeId);
            node.x += dx / zoom;
            node.y += dy / zoom;
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (event.button() == 2) {
            panning = false;
        }
        if (event.button() == 0) {
            draggingNodeId = null;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        double worldXBefore = worldX(mouseX);
        double worldYBefore = worldY(mouseY);
        double amount = scrollY != 0.0 ? scrollY : scrollX;
        zoom = clamp(zoom * (amount > 0 ? 1.12 : 1.0 / 1.12), 0.35, 2.5);
        panX = mouseX - getX() - worldXBefore * zoom;
        panY = mouseY - getY() - worldYBefore * zoom;
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
    }

    private void connectWire(PortHit target) {
        try {
            EditorNode source = document.node(wireStart.nodeId());
            List<PortSpec> sourcePorts = NodePorts.outputs(source, chips);
            if (wireStart.port() < 0 || wireStart.port() >= sourcePorts.size()) {
                status.accept("Invalid source port");
                wireStart = null;
                return;
            }
            int sourceWidth = sourcePorts.get(wireStart.port()).width();
            if (sourceWidth != target.spec.width()) {
                status.accept("WIDTH MISMATCH: " + sourceWidth + "-bit → " + target.spec.width() + "-bit");
                return;
            }
            document.connect(wireStart.nodeId(), wireStart.port(), target.node.id, target.port);
            selectedWire = document.wires.getLast();
            selectedNodeId = null;
            status.accept("Connected " + sourceWidth + "-bit wire");
            wireStart = null;
            recompile();
        } catch (RuntimeException exception) {
            status.accept("Cannot connect: " + exception.getMessage());
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
                status.accept("Simulation error: " + exception.getMessage());
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
        }
    }

    private void clearSelection() {
        selectedNodeId = null;
        selectedWire = null;
        draggingNodeId = null;
        wireStart = null;
    }

    private void drawGrid(GuiGraphicsExtractor graphics) {
        double spacing = 24.0 * zoom;
        if (spacing < 9.0) {
            spacing *= 4.0;
        }
        double startX = getX() + mod(panX, spacing);
        for (double x = startX; x < getX() + width; x += spacing) {
            graphics.fill((int) x, getY(), (int) x + 1, getY() + height, 0xFF191D22);
        }
        double startY = getY() + mod(panY, spacing);
        for (double y = startY; y < getY() + height; y += spacing) {
            graphics.fill(getX(), (int) y, getX() + width, (int) y + 1, 0xFF191D22);
        }
    }

    private void drawNode(GuiGraphicsExtractor graphics, EditorNode node) {
        List<PortSpec> inputs = safeInputs(node);
        List<PortSpec> outputs = safeOutputs(node);
        double nodeHeight = nodeHeight(node);
        int x = screenX(node.x);
        int y = screenY(node.y);
        int w = Math.max(48, (int) Math.round(NODE_WIDTH * zoom));
        int h = Math.max(30, (int) Math.round(nodeHeight * zoom));
        if (x > getX() + width || y > getY() + height || x + w < getX() || y + h < getY()) {
            return;
        }

        int border = selectedNodeId != null && selectedNodeId == node.id ? 0xFFFFFFFF : valueColor(valueForNode(node));
        graphics.fill(x, y, x + w, y + h, 0xF0252930);
        graphics.outline(x, y, w, h, border);
        graphics.fill(x, y, x + w, Math.min(y + h, y + Math.max(13, (int) (22 * zoom))), 0xFF303641);

        graphics.text(nullSafeFont(), truncate(node.displayName(), 18), x + 5, y + 5, 0xFFFFFFFF, true);
        String valueText = node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER
                ? node.width + "-bit"
                : formatValues(valueForNode(node));
        graphics.text(nullSafeFont(), valueText, x + 5, y + Math.max(18, (int) (23 * zoom)), valueColor(valueForNode(node)), true);

        for (int port = 0; port < inputs.size(); port++) {
            Point point = inputPortPoint(node, port);
            drawPort(graphics, point, portColor(node, port, true));
            if (zoom >= 0.72) {
                graphics.text(nullSafeFont(), truncate(inputs.get(port).name(), 7), screenX(point.x) + 7, screenY(point.y) - 4, 0xFFC5CBD3, false);
            }
        }
        for (int port = 0; port < outputs.size(); port++) {
            Point point = outputPortPoint(node, port);
            drawPort(graphics, point, portColor(node, port, false));
            if (zoom >= 0.72) {
                String label = truncate(outputs.get(port).name(), 7);
                graphics.text(nullSafeFont(), label, screenX(point.x) - 7 - nullSafeFont().width(label), screenY(point.y) - 4, 0xFFC5CBD3, false);
            }
        }
    }

    private void drawPort(GuiGraphicsExtractor graphics, Point point, int color) {
        int x = screenX(point.x);
        int y = screenY(point.y);
        int r = Math.max(3, (int) Math.round(4 * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF111111);
    }

    private void drawWire(GuiGraphicsExtractor graphics, WireConnection wire) {
        EditorNode source;
        EditorNode target;
        try {
            source = document.node(wire.sourceNodeId());
            target = document.node(wire.targetNodeId());
        } catch (RuntimeException ignored) {
            return;
        }
        if (wire.sourcePort() >= safeOutputs(source).size() || wire.targetPort() >= safeInputs(target).size()) {
            return;
        }
        Point start = outputPortPoint(source, wire.sourcePort());
        Point end = inputPortPoint(target, wire.targetPort());
        int x1 = screenX(start.x);
        int y1 = screenY(start.y);
        int x2 = screenX(end.x);
        int y2 = screenY(end.y);
        int midX = (x1 + x2) / 2;
        int color = selectedWire != null && selectedWire.equals(wire) ? 0xFFFFFFFF : wireColor(wire);
        int thickness = selectedWire != null && selectedWire.equals(wire) ? 3 : 2;
        drawHorizontal(graphics, x1, midX, y1, thickness, color);
        drawVertical(graphics, midX, y1, y2, thickness, color);
        drawHorizontal(graphics, midX, x2, y2, thickness, color);

        int busWidth = safeOutputs(source).get(wire.sourcePort()).width();
        if (busWidth > 1) {
            graphics.text(nullSafeFont(), "[" + busWidth + "]", midX + 3, (y1 + y2) / 2 - 5, 0xFF8DB7FF, true);
        }
    }

    private int portColor(EditorNode node, int port, boolean input) {
        if (runtime == null) {
            return 0xFF777777;
        }
        try {
            LogicValue[] values = input ? runtime.inputValues(node.id, port) : runtime.outputValues(node.id, port);
            return valueColor(values);
        } catch (RuntimeException ignored) {
            return 0xFF777777;
        }
    }

    private int wireColor(WireConnection wire) {
        if (runtime == null) {
            return 0xFF6B7280;
        }
        try {
            return valueColor(runtime.outputValues(wire.sourceNodeId(), wire.sourcePort()));
        } catch (RuntimeException ignored) {
            return 0xFF6B7280;
        }
    }

    private LogicValue[] valueForNode(EditorNode node) {
        if (runtime == null) {
            return new LogicValue[]{LogicValue.UNKNOWN};
        }
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

    private PortHit outputPortAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeOutputs(node);
            for (int port = 0; port < ports.size(); port++) {
                if (near(mouseX, mouseY, outputPortPoint(node, port), 8.0)) {
                    return new PortHit(node, port, ports.get(port));
                }
            }
        }
        return null;
    }

    private PortHit inputPortAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeInputs(node);
            for (int port = 0; port < ports.size(); port++) {
                if (near(mouseX, mouseY, inputPortPoint(node, port), 8.0)) {
                    return new PortHit(node, port, ports.get(port));
                }
            }
        }
        return null;
    }

    private EditorNode nodeAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            double x = screenX(node.x);
            double y = screenY(node.y);
            double w = NODE_WIDTH * zoom;
            double h = nodeHeight(node) * zoom;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
                return node;
            }
        }
        return null;
    }

    private WireConnection wireAt(double mouseX, double mouseY) {
        for (int i = document.wires.size() - 1; i >= 0; i--) {
            WireConnection wire = document.wires.get(i);
            try {
                EditorNode source = document.node(wire.sourceNodeId());
                EditorNode target = document.node(wire.targetNodeId());
                if (wire.sourcePort() >= safeOutputs(source).size() || wire.targetPort() >= safeInputs(target).size()) {
                    continue;
                }
                Point start = outputPortPoint(source, wire.sourcePort());
                Point end = inputPortPoint(target, wire.targetPort());
                double x1 = screenX(start.x);
                double y1 = screenY(start.y);
                double x2 = screenX(end.x);
                double y2 = screenY(end.y);
                double midX = (x1 + x2) / 2.0;
                if (distanceToSegment(mouseX, mouseY, x1, y1, midX, y1) <= 6.0
                        || distanceToSegment(mouseX, mouseY, midX, y1, midX, y2) <= 6.0
                        || distanceToSegment(mouseX, mouseY, midX, y2, x2, y2) <= 6.0) {
                    return wire;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
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

    private double nodeHeight(EditorNode node) {
        int count = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        return Math.max(58.0, PORT_START_Y + Math.max(1, count) * PORT_STEP + 8.0);
    }

    private Point inputPortPoint(EditorNode node, int port) {
        return new Point(node.x, node.y + PORT_START_Y + port * PORT_STEP);
    }

    private Point outputPortPoint(EditorNode node, int port) {
        return new Point(node.x + NODE_WIDTH, node.y + PORT_START_Y + port * PORT_STEP);
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

    private static int valueColor(LogicValue[] values) {
        if (values == null || values.length == 0) {
            return 0xFF777777;
        }
        boolean anyUnknown = false;
        boolean anyHigh = false;
        boolean anyLow = false;
        for (LogicValue value : values) {
            if (value == LogicValue.UNKNOWN) anyUnknown = true;
            if (value == LogicValue.HIGH) anyHigh = true;
            if (value == LogicValue.LOW) anyLow = true;
        }
        if (anyUnknown) return 0xFFFFC857;
        if (anyHigh && anyLow) return 0xFF5AA9FF;
        if (anyHigh) return 0xFF55D96B;
        return 0xFFE05252;
    }

    private static String formatValues(LogicValue[] values) {
        if (values == null || values.length == 0) return "—";
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

    private static double mod(double value, double divisor) {
        double result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private record Point(double x, double y) {
    }

    private record PortHit(EditorNode node, int port, PortSpec spec) {
    }
}
