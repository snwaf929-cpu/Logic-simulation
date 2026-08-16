package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.block.CircuitWorkerPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/** Per-BOARD simulation parallelism editor. 0 means AUTO; explicit values are worker ceilings, not reservations. */
public final class CircuitWorkerConfigScreen extends Screen {
    private final Screen parent;
    private final int initialWorkers;
    private final int systemMaximum;
    private final IntConsumer apply;

    private EditBox valueBox;
    private String error = "";

    public CircuitWorkerConfigScreen(Screen parent, int initialWorkers, IntConsumer apply) {
        super(Component.literal("Circuit Block Simulation Workers"));
        this.parent = parent;
        this.initialWorkers = CircuitWorkerPolicy.normalizePersisted(initialWorkers);
        this.systemMaximum = CircuitWorkerPolicy.systemMaximum(Runtime.getRuntime().availableProcessors());
        this.apply = apply;
    }

    @Override
    protected void init() {
        int panelW = Math.min(430, Math.max(300, width - 36));
        int x = (width - panelW) / 2;
        int y = Math.max(30, height / 2 - 112);

        valueBox = new EditBox(font, x + 22, y + 72, panelW - 44, 20, Component.literal("Workers"));
        valueBox.setMaxLength(4);
        valueBox.setValue(initialWorkers == CircuitWorkerPolicy.AUTO ? "AUTO" : Integer.toString(initialWorkers));
        addRenderableWidget(valueBox);

        int bw = 68;
        int gap = 8;
        int bx = x + 22;
        addRenderableWidget(new FlatActionButton(bx, y + 101, bw, 20, "AUTO", 0xFF5FA8FF,
                () -> { valueBox.setValue("AUTO"); error = ""; }));
        addRenderableWidget(new FlatActionButton(bx + bw + gap, y + 101, bw, 20, "1", 0xFF5FA8FF,
                () -> { valueBox.setValue("1"); error = ""; }));
        addRenderableWidget(new FlatActionButton(bx + (bw + gap) * 2, y + 101, bw, 20, "MAX " + systemMaximum, 0xFF5FA8FF,
                () -> { valueBox.setValue(Integer.toString(systemMaximum)); error = ""; }));

        addRenderableWidget(new FlatActionButton(x + panelW - 150, y + 171, 62, 21, "APPLY", 0xFF55B96B, this::applyValue));
        addRenderableWidget(new FlatActionButton(x + panelW - 80, y + 171, 58, 21, "CANCEL", 0xFF7B8796, this::onClose));
        setInitialFocus(valueBox);
    }

    private void applyValue() {
        try {
            String raw = valueBox.getValue().trim();
            int requested;
            if (raw.equalsIgnoreCase("AUTO")) {
                requested = CircuitWorkerPolicy.AUTO;
            } else {
                requested = Integer.parseInt(raw);
                if (requested < 1 || requested > systemMaximum) {
                    throw new IllegalArgumentException("Workers must be AUTO or 1.." + systemMaximum);
                }
            }
            if (apply != null) apply.accept(requested);
            onClose();
        } catch (NumberFormatException ignored) {
            error = "Enter AUTO or a whole number from 1 to " + systemMaximum;
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null ? "Invalid worker setting" : exception.getMessage();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD9070A0E);
        int panelW = Math.min(430, Math.max(300, width - 36));
        int x = (width - panelW) / 2;
        int y = Math.max(30, height / 2 - 112);
        int panelH = 210;
        graphics.fill(x, y, x + panelW, y + panelH, 0xFF141A20);
        graphics.outline(x, y, panelW, panelH, 0xFF475563);
        graphics.fill(x, y, x + 4, y + panelH, 0xFF5FA8FF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, "CIRCUIT BLOCK WORKERS", x + 22, y + 17, 0xFFF0F4F7, true);
        graphics.text(font, "Shared simulation pool: " + systemMaximum + " / "
                + Runtime.getRuntime().availableProcessors() + " logical CPUs (25% cap)", x + 22, y + 38, 0xFF96A3B1, false);
        graphics.text(font, "AUTO borrows spare workers; an explicit value is this computer's maximum.", x + 22, y + 53, 0xFF96A3B1, false);
        graphics.text(font, "Workers are not reserved. Disabled or unloaded Circuit Blocks use no continuous compute.", x + 22, y + 132, 0xFFC4A5DE, false);
        graphics.text(font, "Only compile-proven independent regions run in parallel; stateful ordering stays deterministic.", x + 22, y + 147, 0xFFC4A5DE, false);
        if (!error.isBlank()) graphics.text(font, error, x + 22, y + 177, 0xFFE77777, false);
    }
}
