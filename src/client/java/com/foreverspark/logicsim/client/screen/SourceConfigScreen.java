package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** Small exact-value editor for CLOCK frequency and RANDOM probability. */
public final class SourceConfigScreen extends Screen {
    private enum Mode { CLOCK, RANDOM }
    private enum Unit {
        HZ("Hz", 1L), KHZ("kHz", 1_000L), MHZ("MHz", 1_000_000L);
        final String label;
        final long multiplier;
        Unit(String label, long multiplier) { this.label = label; this.multiplier = multiplier; }
    }

    private final Screen parent;
    private final Mode mode;
    private final long initialFrequencyHz;
    private final int initialChancePercent;
    private final LongConsumer frequencyApply;
    private final IntConsumer chanceApply;

    private EditBox valueBox;
    private FlatActionButton hzButton;
    private FlatActionButton khzButton;
    private FlatActionButton mhzButton;
    private Unit unit = Unit.HZ;
    private String error = "";

    private SourceConfigScreen(Screen parent, Mode mode, long initialFrequencyHz, int initialChancePercent,
                               LongConsumer frequencyApply, IntConsumer chanceApply) {
        super(Component.literal(mode == Mode.CLOCK ? "CLOCK Configuration" : "RANDOM Configuration"));
        this.parent = parent;
        this.mode = mode;
        this.initialFrequencyHz = initialFrequencyHz;
        this.initialChancePercent = initialChancePercent;
        this.frequencyApply = frequencyApply;
        this.chanceApply = chanceApply;
    }

    public static SourceConfigScreen clock(Screen parent, long frequencyHz, LongConsumer apply) {
        return new SourceConfigScreen(parent, Mode.CLOCK, frequencyHz, 0, apply, null);
    }

    public static SourceConfigScreen random(Screen parent, int chancePercent, IntConsumer apply) {
        return new SourceConfigScreen(parent, Mode.RANDOM, 0L, chancePercent, null, apply);
    }

    @Override
    protected void init() {
        int panelW = Math.min(360, Math.max(260, width - 36));
        int x = (width - panelW) / 2;
        int y = Math.max(35, height / 2 - 92);

        valueBox = new EditBox(font, x + 22, y + 58, panelW - 44, 20,
                Component.literal(mode == Mode.CLOCK ? "Frequency" : "Chance"));
        valueBox.setMaxLength(18);

        if (mode == Mode.CLOCK) {
            unit = bestUnit(initialFrequencyHz);
            valueBox.setValue(formatForUnit(initialFrequencyHz, unit));
            int bw = 58;
            int gap = 8;
            int total = bw * 3 + gap * 2;
            int bx = x + (panelW - total) / 2;
            hzButton = new FlatActionButton(bx, y + 86, bw, 20, "Hz", 0xFF5FA8FF, () -> setUnit(Unit.HZ));
            khzButton = new FlatActionButton(bx + bw + gap, y + 86, bw, 20, "kHz", 0xFF5FA8FF, () -> setUnit(Unit.KHZ));
            mhzButton = new FlatActionButton(bx + (bw + gap) * 2, y + 86, bw, 20, "MHz", 0xFF5FA8FF, () -> setUnit(Unit.MHZ));
            addRenderableWidget(hzButton);
            addRenderableWidget(khzButton);
            addRenderableWidget(mhzButton);
            refreshUnitLabels();
        } else {
            valueBox.setValue(Integer.toString(Math.max(0, Math.min(100, initialChancePercent))));
        }

        addRenderableWidget(valueBox);
        addRenderableWidget(new FlatActionButton(x + panelW - 142, y + 135, 58, 21, "APPLY", 0xFF55B96B, this::apply));
        addRenderableWidget(new FlatActionButton(x + panelW - 76, y + 135, 54, 21, "CANCEL", 0xFF7B8796, this::onClose));
        setInitialFocus(valueBox);
    }

    private void setUnit(Unit next) {
        if (next == unit) return;
        try {
            long hz = parseFrequency();
            unit = next;
            valueBox.setValue(formatForUnit(hz, unit));
            error = "";
        } catch (RuntimeException ignored) {
            unit = next;
        }
        refreshUnitLabels();
    }

    private void refreshUnitLabels() {
        if (hzButton == null) return;
        hzButton.setMessage(Component.literal(unit == Unit.HZ ? "[Hz]" : "Hz"));
        khzButton.setMessage(Component.literal(unit == Unit.KHZ ? "[kHz]" : "kHz"));
        mhzButton.setMessage(Component.literal(unit == Unit.MHZ ? "[MHz]" : "MHz"));
    }

    private void apply() {
        try {
            if (mode == Mode.CLOCK) {
                long hz = parseFrequency();
                if (frequencyApply != null) frequencyApply.accept(hz);
            } else {
                String raw = valueBox.getValue().trim();
                if (raw.isEmpty()) throw new IllegalArgumentException("Enter a chance from 0 to 100");
                int chance = Integer.parseInt(raw);
                if (chance < 0 || chance > 100) throw new IllegalArgumentException("Chance must be between 0% and 100%");
                if (chanceApply != null) chanceApply.accept(chance);
            }
            onClose();
        } catch (NumberFormatException exception) {
            error = mode == Mode.CLOCK ? "Enter a number, for example 20, 5, or 1.5" : "Enter a whole number from 0 to 100";
        } catch (RuntimeException exception) {
            error = exception.getMessage() == null ? "Invalid value" : exception.getMessage();
        }
    }

    private long parseFrequency() {
        String raw = valueBox.getValue().trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Enter a frequency");
        double amount = Double.parseDouble(raw);
        if (!Double.isFinite(amount) || amount <= 0.0) throw new IllegalArgumentException("Frequency must be greater than 0");
        double exact = amount * unit.multiplier;
        if (exact < 1.0 || exact > ClockPlacementState.MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException("Frequency must be from 1 Hz to 50 MHz");
        }
        return Math.max(1L, Math.min(ClockPlacementState.MAX_FREQUENCY_HZ, Math.round(exact)));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xD9070A0E);
        int panelW = Math.min(360, Math.max(260, width - 36));
        int x = (width - panelW) / 2;
        int y = Math.max(35, height / 2 - 92);
        int panelH = 174;
        graphics.fill(x, y, x + panelW, y + panelH, 0xFF141A20);
        graphics.outline(x, y, panelW, panelH, 0xFF475563);
        graphics.fill(x, y, x + 4, y + panelH, mode == Mode.CLOCK ? 0xFF5FA8FF : 0xFFB06CE8);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(font, mode == Mode.CLOCK ? "CLOCK FREQUENCY" : "RANDOM SOURCE", x + 22, y + 17, 0xFFF0F4F7, true);
        if (mode == Mode.CLOCK) {
            graphics.text(font, "Type an exact value, then choose Hz / kHz / MHz.", x + 22, y + 36, 0xFF96A3B1, false);
            String resolved;
            try { resolved = "Resolved: " + com.foreverspark.logicsim.editor.model.EditorNode.formatFrequency(parseFrequency()); }
            catch (RuntimeException ignored) { resolved = "Range: 1 Hz .. 50 MHz"; }
            graphics.text(font, resolved, x + 22, y + 114, 0xFF8FBFEB, false);
        } else {
            graphics.text(font, "Chance that OUT becomes 1 on each TRIGGER 0 -> 1 edge.", x + 22, y + 36, 0xFF96A3B1, false);
            graphics.text(font, "% HIGH  |  holding TRIGGER at 1 does not roll again", x + 22, y + 88, 0xFFC4A5DE, false);
        }
        if (!error.isBlank()) graphics.text(font, error, x + 22, y + 119, 0xFFE77777, false);
    }

    private static Unit bestUnit(long hz) {
        if (hz >= 1_000_000L && hz % 1_000_000L == 0L) return Unit.MHZ;
        if (hz >= 1_000L && hz % 1_000L == 0L) return Unit.KHZ;
        return Unit.HZ;
    }

    private static String formatForUnit(long hz, Unit unit) {
        double amount = hz / (double)unit.multiplier;
        if (Math.rint(amount) == amount) return Long.toString((long)amount);
        String text = String.format(Locale.ROOT, "%.6f", amount);
        while (text.contains(".") && text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
