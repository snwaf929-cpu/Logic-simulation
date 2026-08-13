package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.CircuitPortCatalog;
import com.foreverspark.logicsim.network.BindCircuitPortPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class CircuitPortBindingScreen extends Screen {
    private static final int ROWS_PER_PAGE = 10;

    private final BlockPos socketPos;
    private final BlockPos circuitPos;
    private final CircuitPortCatalog catalog;
    private final List<Entry> entries = new ArrayList<>();
    private final List<PortChoiceButton> rowButtons = new ArrayList<>();
    private int page;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public CircuitPortBindingScreen(BlockPos socketPos, BlockPos circuitPos, CircuitPortCatalog catalog) {
        super(Component.literal("Circuit I/O Connector"));
        this.socketPos = socketPos.immutable();
        this.circuitPos = circuitPos.immutable();
        this.catalog = catalog;
        for (PortSpec port : catalog.inputs) entries.add(new Entry(port, PortDirection.INPUT));
        for (PortSpec port : catalog.outputs) entries.add(new Entry(port, PortDirection.OUTPUT));
    }

    @Override
    protected void init() {
        panelW = Math.min(Math.max(310, (int)Math.round(width * 0.56)), Math.min(460, width - 24));
        panelH = Math.min(350, Math.max(250, height - 36));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        rowButtons.clear();
        int rowX = panelX + 18;
        int rowW = panelW - 36;
        int firstY = panelY + 64;
        int rowH = 24;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            PortChoiceButton row = new PortChoiceButton(rowX, firstY + i * 26, rowW, rowH);
            rowButtons.add(row);
            addRenderableWidget(row);
        }

        addRenderableWidget(new FlatActionButton(panelX + 18, panelY + panelH - 31, 54, 20, "PREV", 0xFF5E7898, this::previousPage));
        addRenderableWidget(new FlatActionButton(panelX + 78, panelY + panelH - 31, 54, 20, "NEXT", 0xFF5E7898, this::nextPage));
        addRenderableWidget(new FlatActionButton(panelX + panelW - 76, panelY + panelH - 31, 58, 20, "CANCEL", 0xFF7B8796, this::onClose));
        populateRows();
    }

    private void populateRows() {
        int start = page * ROWS_PER_PAGE;
        for (int i = 0; i < rowButtons.size(); i++) {
            PortChoiceButton button = rowButtons.get(i);
            int index = start + i;
            if (index >= entries.size()) {
                button.clearChoice();
                continue;
            }
            Entry entry = entries.get(index);
            boolean supported = supportsPhysicalCable(entry.port.width());
            button.setChoice(entry.port, entry.direction, supported, () -> choose(entry));
        }
    }

    private void previousPage() {
        if (page <= 0) return;
        page--;
        populateRows();
    }

    private void nextPage() {
        int pages = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (page + 1 >= pages) return;
        page++;
        populateRows();
    }

    private void choose(Entry entry) {
        if (!supportsPhysicalCable(entry.port.width())) return;
        ClientPlayNetworking.send(new BindCircuitPortPayload(socketPos, circuitPos, entry.port.name(), entry.direction.name()));
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD9070A0E);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF141A20);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF475563);
        graphics.fill(panelX, panelY, panelX + 4, panelY + panelH, 0xFF4FA6A0);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        String name = catalog.circuitName == null || catalog.circuitName.isBlank() ? "CIRCUIT" : catalog.circuitName;
        graphics.text(font, "I/O CONNECTOR", panelX + 18, panelY + 15, 0xFFF0F4F7, true);
        graphics.text(font, name + "  •  choose one external pin", panelX + 18, panelY + 32, 0xFF96A3B1, false);
        graphics.text(font, "IN = cable drives circuit   OUT = circuit drives cable", panelX + 18, panelY + 47, 0xFF657482, false);

        int pages = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        String pageText = "PAGE " + (page + 1) + "/" + pages;
        graphics.text(font, pageText, panelX + panelW - 18 - font.width(pageText), panelY + 15, 0xFF718091, false);
        if (entries.isEmpty()) graphics.text(font, "This Circuit Block has no external INPUT/OUTPUT pins.", panelX + 18, panelY + 82, 0xFFE28A8A, false);
    }

    private static boolean supportsPhysicalCable(int width) {
        return width == 1 ? CableKind.SIGNAL.supportsWidth(width) : CableKind.BUS.supportsWidth(width);
    }

    private record Entry(PortSpec port, PortDirection direction) {}
}
