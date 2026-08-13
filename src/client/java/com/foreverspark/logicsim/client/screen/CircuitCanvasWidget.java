package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Freeform cubic circuit canvas backed by the real NAND compiler/simulator. */
public final class CircuitCanvasWidget extends AbstractWidget {
    private static final double PORT_START_Y = 29.0;
    private static final double PORT_STEP = 15.0;
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

    private boolean panning;
    private int panButton = -1;
    private double panX = 36.0;
    private double panY = 34.0;
    private double zoom = 1.0;

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
        status.accept("Place " + kind.name() + " — click anywhere on the canvas");
    }

    public void setCustomChipPlacement(String chipName) {
        if (chipName == null || chipName.isBlank() || chips.find(chipName.trim()) == null) {
            status.accept("Saved chip not found: " + (chipName == null ? "" : chipName));
            return;
        }
        placementKind = NodeKind.CUSTOM_CHIP;
        placementChipName = chipName.trim();
        wireStart = null;
        status.accept("Place " + placementChipName + " — drag its library row onto a folder to organize it");
    }

    public void cancelPlacement() {
        placementKind = null;
        placementChipName = null;
        wireStart = null;
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
            return;
        }
        status.accept("Select a node or wire first");
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

        node.width = WIDTHS[next];
        document.removeWiresForNode(node.id);
        inputStates.put(node.id, 0L);
        wireStart = null;
        selectedWire = null;
        recompile();
        status.accept(node.displayName() + " width = " + node.width + " bit; attached wires were cleared");
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

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = getX();
        int top = getY();
        graphics.fill(left, top, left + width, top + height, 0xF00D1014);
        graphics.outline(left, top, width, height, 0xFF343C46);
        drawGrid(graphics);

        for (WireConnection wire : document.wires) drawWire(graphics, wire);
        if (wireStart != null) {
            EditorNode source = document.node(wireStart.nodeId());
            Point start = outputPortPoint(source, wireStart.port());
            int sx = screenX(start.x);
            int sy = screenY(start.y);
            graphics.outline(sx - 5, sy - 5, 11, 11, 0xFFFFFFFF);
        }
        for (EditorNode node : document.nodes) drawNode(graphics, node);

        String mode = placementKind == null
                ? wireStart == null ? "SELECT" : "WIRE"
                : placementKind == NodeKind.CUSTOM_CHIP ? "PLACE " + placementChipName : "PLACE " + placementKind;
        graphics.text(font(), mode + "   " + Math.round(zoom * 100) + "%", left + 7, top + 7, 0xFF84909E, false);

        if (compileError != null) {
            graphics.text(font(), "ERROR: " + truncate(compileError, 80), left + 7, top + height - 15, 0xFFFF6B6B, false);
        } else {
            graphics.text(font(), "Drag empty space = pan  •  wheel = zoom  •  click INPUT switch = 0/1  •  OUT -> IN = wire", left + 7, top + height - 15, 0xFF697583, false);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (!contains(mouseX, mouseY)) return;

        if (button == 2) {
            beginPan(2);
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
            if (nodeAt(mouseX, mouseY) == null) {
                beginPan(1);
                return;
            }
            cancelPlacement();
            status.accept("Placement/wire cancelled");
            return;
        }

        if (button != 0) return;

        if (placementKind != null) {
            EditorNode node = placementKind == NodeKind.CUSTOM_CHIP
                    ? document.addCustomChip(placementChipName, worldX(mouseX), worldY(mouseY))
                    : document.addNode(placementKind, worldX(mouseX), worldY(mouseY));
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
            status.accept("Wire: " + outputHit.spec.width() + "-bit " + outputHit.node.displayName() + "." + outputHit.spec.name() + " -> choose an input");
            return;
        }

        PortHit inputHit = inputPortAt(mouseX, mouseY);
        if (inputHit != null && wireStart != null) {
            connectWire(inputHit);
            return;
        }

        EditorNode node = nodeAt(mouseX, mouseY);
        if (node != null && node.kind == NodeKind.INPUT && inputToggleHit(node, mouseX, mouseY)) {
            selectedNodeId = node.id;
            selectedWire = null;
            toggleInput(node);
            return;
        }

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
        beginPan(0);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (panning && event.button() == panButton) {
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
        if (panning && event.button() == panButton) {
            panning = false;
            panButton = -1;
        }
        if (event.button() == 0) draggingNodeId = null;
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
                status.accept("WIDTH MISMATCH: " + sourceWidth + "-bit -> " + target.spec.width() + "-bit. Use Splitter/Merger.");
                return;
            }
            document.connect(wireStart.nodeId(), wireStart.port(), target.node.id, target.port);
            selectedWire = document.wires.getLast();
            selectedNodeId = null;
            wireStart = null;
            recompile();
            status.accept("Connected " + sourceWidth + "-bit " + (sourceWidth == 1 ? "wire" : "bus"));
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
        graphics.text(font(), truncate(title, node.kind == NodeKind.CUSTOM_CHIP ? 16 : 11), x + 5, y + 8, 0xFFF1F4F7, false);

        if (node.kind == NodeKind.INPUT) {
            drawInputSwitch(graphics, node, x, y, w, h);
        } else if (node.kind == NodeKind.OUTPUT) {
            drawValueBox(graphics, valueForNode(node), x + 5, y + h - Math.max(18, (int) (19 * zoom)), Math.max(30, w - 10), Math.max(13, (int) (14 * zoom)), false);
        } else if (node.kind == NodeKind.NAND) {
            String value = formatValues(valueForNode(node));
            graphics.text(font(), value, x + 5, y + h - 14, valueColor(valueForNode(node)), false);
        } else if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) {
            graphics.text(font(), node.width + " bit", x + 5, y + 21, 0xFF8DA0B5, false);
        } else {
            graphics.text(font(), formatValues(valueForNode(node)), x + 5, y + 21, valueColor(valueForNode(node)), false);
        }

        for (int port = 0; port < inputs.size(); port++) {
            Point point = inputPortPoint(node, port);
            drawPort(graphics, point, portColor(node, port, true));
            if (zoom >= 0.78 && shouldShowPortLabel(node, inputs.size())) {
                graphics.text(font(), truncate(inputs.get(port).name(), 6), screenX(point.x) + 7, screenY(point.y) - 4, 0xFFAAB4C0, false);
            }
        }
        for (int port = 0; port < outputs.size(); port++) {
            Point point = outputPortPoint(node, port);
            drawPort(graphics, point, portColor(node, port, false));
            if (zoom >= 0.78 && shouldShowPortLabel(node, outputs.size())) {
                String label = truncate(outputs.get(port).name(), 6);
                graphics.text(font(), label, screenX(point.x) - 7 - font().width(label), screenY(point.y) - 4, 0xFFAAB4C0, false);
            }
        }
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

    private void drawValueBox(GuiGraphicsExtractor graphics, LogicValue[] values, int x, int y, int w, int h, boolean interactive) {
        int col = darken(valueColor(values), 0.55);
        graphics.fill(x, y, x + w, y + h, col);
        graphics.outline(x, y, w, h, valueColor(values));
        graphics.text(font(), formatValues(values), x + 4, y + 3, 0xFFFFFFFF, false);
    }

    private boolean shouldShowPortLabel(EditorNode node, int portCount) {
        return portCount <= 16 && node.kind != NodeKind.INPUT && node.kind != NodeKind.OUTPUT;
    }

    private void drawPort(GuiGraphicsExtractor graphics, Point point, int color) {
        int x = screenX(point.x);
        int y = screenY(point.y);
        int r = Math.max(3, (int) Math.round(3.5 * zoom));
        graphics.fill(x - r, y - r, x + r + 1, y + r + 1, color);
        graphics.outline(x - r - 1, y - r - 1, r * 2 + 3, r * 2 + 3, 0xFF090B0D);
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
        if (wire.sourcePort() >= safeOutputs(source).size() || wire.targetPort() >= safeInputs(target).size()) return;

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
            graphics.fill(midX + 2, (y1 + y2) / 2 - 7, midX + 25, (y1 + y2) / 2 + 5, 0xFF11161C);
            graphics.text(font(), "[" + busWidth + "]", midX + 4, (y1 + y2) / 2 - 5, 0xFF8DB7FF, false);
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
                if (near(mouseX, mouseY, inputPortPoint(node, port), 8.0)) return new PortHit(node, port, ports.get(port));
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
            try {
                EditorNode source = document.node(wire.sourceNodeId());
                EditorNode target = document.node(wire.targetNodeId());
                if (wire.sourcePort() >= safeOutputs(source).size() || wire.targetPort() >= safeInputs(target).size()) continue;
                Point start = outputPortPoint(source, wire.sourcePort());
                Point end = inputPortPoint(target, wire.targetPort());
                double x1 = screenX(start.x);
                double y1 = screenY(start.y);
                double x2 = screenX(end.x);
                double y2 = screenY(end.y);
                double midX = (x1 + x2) / 2.0;
                if (distanceToSegment(mouseX, mouseY, x1, y1, midX, y1) <= 6.0
                        || distanceToSegment(mouseX, mouseY, midX, y1, midX, y2) <= 6.0
                        || distanceToSegment(mouseX, mouseY, midX, y2, x2, y2) <= 6.0) return wire;
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

    private double nodeWidth(EditorNode node) {
        return switch (node.kind) {
            case INPUT, OUTPUT -> 72.0;
            case NAND -> 78.0;
            case SPLITTER, MERGER -> 102.0;
            case CUSTOM_CHIP -> 108.0;
        };
    }

    private double nodeHeight(EditorNode node) {
        if (node.kind == NodeKind.INPUT || node.kind == NodeKind.OUTPUT) return 43.0;
        int count = Math.max(safeInputs(node).size(), safeOutputs(node).size());
        if (node.kind == NodeKind.NAND) return 55.0;
        return Math.max(48.0, PORT_START_Y + Math.max(1, count) * PORT_STEP + 8.0);
    }

    private Point inputPortPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case OUTPUT, SPLITTER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + PORT_START_Y + port * PORT_STEP;
        };
        return new Point(node.x, y);
    }

    private Point outputPortPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case INPUT, NAND, MERGER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + PORT_START_Y + port * PORT_STEP;
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

    private static double mod(double value, double divisor) {
        double result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private record Point(double x, double y) {
    }

    private record PortHit(EditorNode node, int port, PortSpec spec) {
    }
}
