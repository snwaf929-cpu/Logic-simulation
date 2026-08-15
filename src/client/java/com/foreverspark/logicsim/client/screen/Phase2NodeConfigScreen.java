package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.client.screen.v2.NumericValueCodec;
import com.foreverspark.logicsim.editor.model.BusSliceOutput;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Exact Phase 2 configuration UI for arbitrary widths, values, bus slices, and electrical net labels. */
public final class Phase2NodeConfigScreen extends Screen {
    private enum Mode { VALUE, WIDTH, SLICE, NET }

    @FunctionalInterface public interface ValueApply { void apply(int width, long value); }
    @FunctionalInterface public interface WidthApply { void apply(int width, int laneWidth); }
    @FunctionalInterface public interface SliceApply { void apply(int inputWidth, List<BusSliceOutput> slices); }
    @FunctionalInterface public interface NetApply { void apply(String name, int width); }

    private final Screen parent;
    private final Mode mode;
    private final String heading;
    private final int initialWidth;
    private final int initialLaneWidth;
    private final long initialValue;
    private final String initialName;
    private final List<BusSliceOutput> initialSlices;
    private final ValueApply valueApply;
    private final WidthApply widthApply;
    private final SliceApply sliceApply;
    private final NetApply netApply;

    private EditBox widthBox;
    private EditBox laneBox;
    private EditBox hexBox;
    private EditBox decBox;
    private EditBox binBox;
    private EditBox nameBox;
    private EditBox slicesBox;
    private boolean synchronizing;
    private long currentValue;
    private String error = "";

    private Phase2NodeConfigScreen(Screen parent, Mode mode, String heading, int width, int laneWidth, long value,
                                   String name, List<BusSliceOutput> slices,
                                   ValueApply valueApply, WidthApply widthApply, SliceApply sliceApply, NetApply netApply) {
        super(Component.literal(heading));
        this.parent = parent;
        this.mode = mode;
        this.heading = heading;
        this.initialWidth = clampWidth(width);
        this.initialLaneWidth = Math.max(1, laneWidth);
        this.initialValue = value;
        this.initialName = name == null ? "" : name;
        this.initialSlices = copySlices(slices);
        this.valueApply = valueApply;
        this.widthApply = widthApply;
        this.sliceApply = sliceApply;
        this.netApply = netApply;
    }

    public static Phase2NodeConfigScreen value(Screen parent, String heading, int width, long value, ValueApply apply) {
        return new Phase2NodeConfigScreen(parent, Mode.VALUE, heading, width, 1, value, "", List.of(), apply, null, null, null);
    }

    public static Phase2NodeConfigScreen width(Screen parent, String heading, int width, int laneWidth, WidthApply apply) {
        return new Phase2NodeConfigScreen(parent, Mode.WIDTH, heading, width, laneWidth, 0L, "", List.of(), null, apply, null, null);
    }

    public static Phase2NodeConfigScreen slice(Screen parent, int inputWidth, List<BusSliceOutput> slices, SliceApply apply) {
        return new Phase2NodeConfigScreen(parent, Mode.SLICE, "BUS SLICE", inputWidth, 1, 0L, "", slices, null, null, apply, null);
    }

    public static Phase2NodeConfigScreen net(Screen parent, String name, int width, NetApply apply) {
        return new Phase2NodeConfigScreen(parent, Mode.NET, "NET LABEL", width, 1, 0L, name, List.of(), null, null, null, apply);
    }

    @Override
    protected void init() {
        int panelW = Math.min(500, Math.max(320, width - 40));
        int panelH = mode == Mode.VALUE ? 245 : mode == Mode.SLICE ? 215 : 185;
        int x = (width - panelW) / 2;
        int y = Math.max(24, (height - panelH) / 2);
        int fieldX = x + 112;
        int fieldW = panelW - 136;

        widthBox = edit(fieldX, y + 56, 86, "Width");
        widthBox.setValue(Integer.toString(initialWidth));
        addRenderableWidget(widthBox);

        if (mode == Mode.VALUE) {
            currentValue = initialValue & NumericValueCodec.mask(initialWidth);
            hexBox = edit(fieldX, y + 86, fieldW, "HEX");
            decBox = edit(fieldX, y + 116, fieldW, "DEC");
            binBox = edit(fieldX, y + 146, fieldW, "BIN");
            hexBox.setMaxLength(20);
            decBox.setMaxLength(24);
            binBox.setMaxLength(96);
            synchronizeValueBoxes(initialWidth);
            hexBox.setResponder(value -> syncFrom(NumericValueCodec.Radix.HEX, value));
            decBox.setResponder(value -> syncFrom(NumericValueCodec.Radix.DEC, value));
            binBox.setResponder(value -> syncFrom(NumericValueCodec.Radix.BIN, value));
            widthBox.setResponder(value -> {
                if (synchronizing) return;
                try {
                    int w = parseWidth();
                    currentValue &= NumericValueCodec.mask(w);
                    synchronizeValueBoxes(w);
                    error = "";
                } catch (RuntimeException exception) {
                    error = message(exception);
                }
            });
            addRenderableWidget(hexBox);
            addRenderableWidget(decBox);
            addRenderableWidget(binBox);
        } else if (mode == Mode.WIDTH) {
            laneBox = edit(fieldX, y + 86, 86, "Lane width");
            laneBox.setValue(Integer.toString(initialLaneWidth));
            addRenderableWidget(laneBox);
        } else if (mode == Mode.SLICE) {
            slicesBox = edit(fieldX, y + 91, fieldW, "Slices");
            slicesBox.setMaxLength(512);
            slicesBox.setValue(formatSlices(initialSlices));
            addRenderableWidget(slicesBox);
        } else {
            nameBox = edit(fieldX, y + 86, fieldW, "Net name");
            nameBox.setMaxLength(48);
            nameBox.setValue(initialName);
            addRenderableWidget(nameBox);
        }

        int buttonsY = y + panelH - 38;
        addRenderableWidget(new FlatActionButton(x + panelW - 150, buttonsY, 60, 21, "APPLY", 0xFF55B96B, this::apply));
        addRenderableWidget(new FlatActionButton(x + panelW - 82, buttonsY, 58, 21, "CANCEL", 0xFF7B8796, this::onClose));
        setInitialFocus(mode == Mode.NET ? nameBox : widthBox);
    }

    private EditBox edit(int x, int y, int w, String label) {
        EditBox box = new EditBox(font, x, y, w, 20, Component.literal(label));
        box.setMaxLength(64);
        return box;
    }

    private void syncFrom(NumericValueCodec.Radix radix, String raw) {
        if (synchronizing) return;
        try {
            int width = parseWidth();
            currentValue = NumericValueCodec.parse(raw, radix, width);
            synchronizeValueBoxes(width);
            error = "";
        } catch (RuntimeException exception) {
            error = message(exception);
        }
    }

    private void synchronizeValueBoxes(int width) {
        if (hexBox == null) return;
        synchronizing = true;
        try {
            hexBox.setValue(NumericValueCodec.hex(currentValue, width));
            decBox.setValue(NumericValueCodec.dec(currentValue, width));
            binBox.setValue(NumericValueCodec.bin(currentValue, width));
        } finally {
            synchronizing = false;
        }
    }

    private void apply() {
        try {
            int width = parseWidth();
            switch (mode) {
                case VALUE -> {
                    currentValue &= NumericValueCodec.mask(width);
                    if (valueApply != null) valueApply.apply(width, currentValue);
                }
                case WIDTH -> {
                    int lane = laneBox == null || laneBox.getValue().isBlank() ? 1 : Integer.parseInt(laneBox.getValue().trim());
                    if (lane < 1 || lane > width || width % lane != 0) {
                        throw new IllegalArgumentException("Lane width must divide the bus width exactly");
                    }
                    if (widthApply != null) widthApply.apply(width, lane);
                }
                case SLICE -> {
                    List<BusSliceOutput> slices = parseSlices(slicesBox.getValue(), width);
                    if (sliceApply != null) sliceApply.apply(width, slices);
                }
                case NET -> {
                    String name = nameBox.getValue().trim();
                    if (name.isEmpty()) throw new IllegalArgumentException("Net label name is required");
                    if (netApply != null) netApply.apply(name, width);
                }
            }
            onClose();
        } catch (NumberFormatException exception) {
            error = "Widths must be whole numbers";
        } catch (RuntimeException exception) {
            error = message(exception);
        }
    }

    private int parseWidth() {
        String raw = widthBox.getValue().trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Width is required");
        int width = Integer.parseInt(raw);
        if (width < 1 || width > 64) throw new IllegalArgumentException("Width must be from 1 to 64");
        return width;
    }

    public static List<BusSliceOutput> parseSlices(String text, int inputWidth) {
        inputWidth = clampWidth(inputWidth);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Add at least one slice");
        String[] definitions = text.split(",");
        List<BusSliceOutput> result = new ArrayList<>();
        for (int i = 0; i < definitions.length; i++) {
            String definition = definitions[i].trim();
            if (definition.isEmpty()) continue;
            String[] nameAndRange = definition.split("=", 2);
            if (nameAndRange.length != 2) throw new IllegalArgumentException("Use NAME=start:width for every slice");
            String name = nameAndRange[0].trim();
            String[] range = nameAndRange[1].trim().split(":", 2);
            if (name.isEmpty() || range.length != 2) throw new IllegalArgumentException("Use NAME=start:width for every slice");
            int start = Integer.parseInt(range[0].trim());
            int width = Integer.parseInt(range[1].trim());
            if (start < 0 || width < 1 || start + width > inputWidth) {
                throw new IllegalArgumentException(name + " range exceeds the " + inputWidth + "-bit input");
            }
            result.add(new BusSliceOutput(name, start, width));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Add at least one slice");
        return List.copyOf(result);
    }

    public static String formatSlices(List<BusSliceOutput> slices) {
        if (slices == null || slices.isEmpty()) return "OUT0=0:1";
        StringBuilder text = new StringBuilder();
        for (BusSliceOutput slice : slices) {
            if (slice == null) continue;
            if (!text.isEmpty()) text.append(", ");
            text.append(slice.name).append('=').append(slice.startBit).append(':').append(slice.width);
        }
        return text.isEmpty() ? "OUT0=0:1" : text.toString();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD9070A0E);
        int panelW = Math.min(500, Math.max(320, width - 40));
        int panelH = mode == Mode.VALUE ? 245 : mode == Mode.SLICE ? 215 : 185;
        int x = (width - panelW) / 2;
        int y = Math.max(24, (height - panelH) / 2);
        graphics.fill(x, y, x + panelW, y + panelH, 0xFF141A20);
        graphics.outline(x, y, panelW, panelH, 0xFF475563);
        graphics.fill(x, y, x + 4, y + panelH, accent());
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, heading, x + 22, y + 17, 0xFFF0F4F7, true);
        graphics.text(font, subtitle(), x + 22, y + 35, 0xFF96A3B1, false);
        graphics.text(font, "WIDTH 1-64", x + 22, y + 62, 0xFF7F8B98, false);
        if (mode == Mode.VALUE) {
            graphics.text(font, "HEX", x + 22, y + 92, 0xFF7F8B98, false);
            graphics.text(font, "DEC", x + 22, y + 122, 0xFF7F8B98, false);
            graphics.text(font, "BIN", x + 22, y + 152, 0xFF7F8B98, false);
        } else if (mode == Mode.WIDTH) {
            graphics.text(font, "LANE", x + 22, y + 92, 0xFF7F8B98, false);
            graphics.text(font, "For normal nodes leave lane = 1. Split/Merge requires an exact divisor.", x + 22, y + 119, 0xFF6E7B88, false);
        } else if (mode == Mode.SLICE) {
            graphics.text(font, "SLICES", x + 22, y + 97, 0xFF7F8B98, false);
            graphics.text(font, "Syntax: OPCODE=12:4, OPERAND=0:12   (start bit : width)", x + 22, y + 124, 0xFF7FA7C8, false);
        } else {
            graphics.text(font, "NAME", x + 22, y + 92, 0xFF7F8B98, false);
            graphics.text(font, "Labels with the same name are one electrical net; exactly one driver is allowed.", x + 22, y + 119, 0xFF7FA7C8, false);
        }
        if (!error.isBlank()) graphics.text(font, "! " + error, x + 22, y + panelH - 59, 0xFFE77777, false);
    }

    private int accent() {
        return switch (mode) {
            case VALUE -> 0xFFD29A45;
            case WIDTH -> 0xFF4FA6A0;
            case SLICE -> 0xFF55AFC2;
            case NET -> 0xFF8E73D8;
        };
    }

    private String subtitle() {
        return switch (mode) {
            case VALUE -> "All three representations stay synchronized.";
            case WIDTH -> "Any integer width from 1 to 64 is valid.";
            case SLICE -> "Create arbitrary named ranges from one input bus.";
            case NET -> "Electrical routing label — not decorative text.";
        };
    }

    private static int clampWidth(int width) { return Math.max(1, Math.min(64, width)); }
    private static String message(RuntimeException exception) { return exception.getMessage() == null ? "Invalid value" : exception.getMessage(); }
    private static List<BusSliceOutput> copySlices(List<BusSliceOutput> slices) {
        List<BusSliceOutput> result = new ArrayList<>();
        if (slices != null) for (BusSliceOutput slice : slices) if (slice != null) result.add(slice.copy());
        return List.copyOf(result);
    }
}
