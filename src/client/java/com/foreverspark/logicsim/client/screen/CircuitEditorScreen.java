package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.core.CircuitSimulator;
import com.foreverspark.logicsim.core.LogicCircuit;
import com.foreverspark.logicsim.core.LogicValue;
import com.foreverspark.logicsim.core.Signal;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * First playable circuit editor milestone.
 * Two input nodes drive one real NAND gate from the simulation core.
 */
public final class CircuitEditorScreen extends Screen {
    private final LogicCircuit circuit = new LogicCircuit();
    private final Signal inputA = circuit.signal("Demo/InputA", LogicValue.LOW);
    private final Signal inputB = circuit.signal("Demo/InputB", LogicValue.LOW);
    private final Signal output = circuit.signal("Demo/NAND/OUT", LogicValue.UNKNOWN);
    private final CircuitSimulator simulator;

    private boolean inputAHigh;
    private boolean inputBHigh;

    public CircuitEditorScreen() {
        super(Component.translatable("screen.logicsimulation.circuit_editor"));
        circuit.nand("Demo/NAND", inputA, inputB, output);
        simulator = new CircuitSimulator(circuit);
        simulator.scheduleAll();
        simulator.runUntilStable(64);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 100;

        this.addRenderableWidget(Button.builder(Component.literal("Toggle A"), button -> {
            inputAHigh = !inputAHigh;
            simulator.drive(inputA, LogicValue.fromBoolean(inputAHigh));
            simulator.runUntilStable(64);
        }).bounds(centerX - 140, buttonY, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Toggle B"), button -> {
            inputBHigh = !inputBHigh;
            simulator.drive(inputB, LogicValue.fromBoolean(inputBHigh));
            simulator.runUntilStable(64);
        }).bounds(centerX - 40, buttonY, 90, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(centerX + 60, buttonY, 80, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int centerY = this.height / 2 - 10;

        int panelLeft = centerX - 250;
        int panelTop = centerY - 120;
        int panelRight = centerX + 250;
        int panelBottom = centerY + 145;
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xE0181818);
        graphics.outline(panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop, 0xFF555555);

        centeredText(graphics, "Circuit Editor — first live NAND test", centerX, panelTop + 14, 0xFFFFFFFF);
        centeredText(graphics, "Both inputs are connected to the real event-driven simulation core.", centerX, panelTop + 32, 0xFFAAAAAA);

        int inputX = centerX - 190;
        int gateX = centerX - 35;
        int outputX = centerX + 135;
        int nodeWidth = 110;
        int nodeHeight = 54;

        int inputAY = centerY - 55;
        int inputBY = centerY + 25;
        int gateY = centerY - 15;
        int outputY = gateY;

        drawWire(graphics, inputX + nodeWidth, inputAY + nodeHeight / 2, gateX, gateY + 16, inputA.value());
        drawWire(graphics, inputX + nodeWidth, inputBY + nodeHeight / 2, gateX, gateY + nodeHeight - 16, inputB.value());
        drawWire(graphics, gateX + nodeWidth, gateY + nodeHeight / 2, outputX, outputY + nodeHeight / 2, output.value());

        drawNode(graphics, inputX, inputAY, nodeWidth, nodeHeight, "INPUT A", inputA.value());
        drawNode(graphics, inputX, inputBY, nodeWidth, nodeHeight, "INPUT B", inputB.value());
        drawNode(graphics, gateX, gateY, nodeWidth, nodeHeight, "NAND", output.value());
        drawNode(graphics, outputX, outputY, nodeWidth, nodeHeight, "OUTPUT", output.value());

        centeredText(
                graphics,
                "NAND(" + bit(inputA.value()) + ", " + bit(inputB.value()) + ") = " + bit(output.value()),
                centerX,
                centerY + 82,
                0xFFFFFFFF
        );
    }

    private void drawNode(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            String label,
            LogicValue value
    ) {
        graphics.fill(x, y, x + width, y + height, 0xFF242424);
        graphics.outline(x, y, width, height, valueColor(value));
        centeredText(graphics, label, x + width / 2, y + 12, 0xFFFFFFFF);
        centeredText(graphics, "VALUE: " + bit(value), x + width / 2, y + 31, valueColor(value));
    }

    private void drawWire(
            GuiGraphicsExtractor graphics,
            int x1,
            int y1,
            int x2,
            int y2,
            LogicValue value
    ) {
        int color = valueColor(value);
        int midX = (x1 + x2) / 2;
        graphics.fill(x1, y1 - 1, midX, y1 + 2, color);
        graphics.fill(midX - 1, Math.min(y1, y2), midX + 2, Math.max(y1, y2) + 1, color);
        graphics.fill(midX, y2 - 1, x2, y2 + 2, color);
    }

    private void centeredText(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        graphics.text(this.font, text, centerX - this.font.width(text) / 2, y, color, true);
    }

    private static int valueColor(LogicValue value) {
        return switch (value) {
            case LOW -> 0xFFE05252;
            case HIGH -> 0xFF55D96B;
            case UNKNOWN -> 0xFFFFC857;
        };
    }

    private static String bit(LogicValue value) {
        return switch (value) {
            case LOW -> "0";
            case HIGH -> "1";
            case UNKNOWN -> "X";
        };
    }
}
