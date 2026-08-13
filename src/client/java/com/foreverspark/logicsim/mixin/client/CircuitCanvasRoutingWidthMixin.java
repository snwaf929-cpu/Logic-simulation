package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = CircuitCanvasWidget.class, priority = 1100)
public abstract class CircuitCanvasRoutingWidthMixin {
    /** A pass-through connector can carry either one signal bit or a packed multi-bit bus. */
    @Unique private static final int[] CONNECTOR_WIDTHS = {1, 2, 4, 8, 16, 32};
    /** Splitting/merging one bit would be meaningless, so these start at two bits. */
    @Unique private static final int[] PACKED_WIDTHS = {2, 4, 8, 16, 32};

    @Shadow private CircuitDocument document;
    @Shadow private NodeKind placementKind;
    @Shadow private Integer selectedNodeId;
    @Shadow @Final private Consumer<String> status;
    @Shadow private void recompile() { throw new AssertionError(); }

    @Unique private boolean logic$pickerOpen;
    @Unique private int logic$width = 1;
    @Unique private int logic$lastConnectorWidth = 1;
    @Unique private int logic$lastSplitterWidth = 8;
    @Unique private int logic$lastMergerWidth = 8;
    @Unique private NodeKind logic$beforePlacement;

    @Inject(method = "setPlacement", at = @At("TAIL"))
    private void logic$setPlacement(NodeKind kind, CallbackInfo ci) {
        logic$pickerOpen = logic$isRouting(kind);
        if (!logic$pickerOpen) return;

        logic$width = switch (kind) {
            case BUS -> logic$lastConnectorWidth;
            case SPLITTER -> logic$lastSplitterWidth;
            case MERGER -> logic$lastMergerWidth;
            default -> logic$width;
        };

        status.accept(logic$title(kind) + " — choose " + logic$optionsText(kind));
    }

    @Inject(method = "cancelPlacement", at = @At("TAIL"))
    private void logic$cancel(CallbackInfo ci) {
        logic$pickerOpen = false;
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$click(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (event.button() != 0) return;
        if (logic$pickerOpen) {
            int selected = logic$buttonAt(event.x(), event.y());
            if (selected > 0) {
                logic$width = selected;
                logic$rememberWidth(placementKind, selected);
                logic$pickerOpen = false;
                String type = placementKind == NodeKind.BUS && selected == 1 ? "WIRE CONNECTOR" : logic$title(placementKind);
                status.accept(type + " = " + selected + " bit — click the canvas to place it");
            } else {
                status.accept("Choose a width first: " + logic$optionsText(placementKind));
            }
            ci.cancel();
            return;
        }
        logic$beforePlacement = placementKind;
    }

    @Inject(method = "onClick", at = @At("TAIL"))
    private void logic$afterClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        NodeKind before = logic$beforePlacement;
        logic$beforePlacement = null;
        if (event.button() != 0 || !logic$isRouting(before) || placementKind != null || selectedNodeId == null) return;
        try {
            EditorNode node = document.node(selectedNodeId);
            if (node.kind != before) return;
            node.width = logic$width;
            recompile();
            String type = before == NodeKind.BUS && logic$width == 1 ? "WIRE CONNECTOR" : logic$title(before);
            status.accept("Placed " + type + " — " + logic$width + " bit");
        } catch (RuntimeException exception) {
            status.accept("ERROR: " + exception.getMessage());
        }
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!logic$pickerOpen || !logic$isRouting(placementKind)) return;
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int[] options = logic$options(placementKind);
        int panelWidth = logic$panelWidth(options);
        int x = self.getX() + 10;
        int y = self.getY() + 25;
        graphics.fill(x, y, x + panelWidth, y + 43, 0xF0161B21);
        graphics.outline(x, y, panelWidth, 43, 0xFF4B5968);
        graphics.text(net.minecraft.client.Minecraft.getInstance().font, logic$title(placementKind), x + 7, y + 6, 0xFFE6ECF3, false);

        int bx = x + 7;
        int by = y + 21;
        var font = net.minecraft.client.Minecraft.getInstance().font;
        for (int width : options) {
            int bw = logic$buttonWidth(width);
            boolean active = width == logic$width;
            boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 17;
            graphics.fill(bx, by, bx + bw, by + 17, active ? 0xFF294866 : hover ? 0xFF29313A : 0xFF20262D);
            graphics.outline(bx, by, bw, 17, active ? 0xFF6CA9FF : hover ? 0xFF66788A : 0xFF3E4955);
            String text = Integer.toString(width);
            graphics.text(font, text, bx + (bw - font.width(text)) / 2, by + 5, 0xFFF0F4F8, false);
            bx += bw + 5;
        }
    }

    @Unique private int logic$buttonAt(double mouseX, double mouseY) {
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        int x = self.getX() + 17;
        int y = self.getY() + 46;
        for (int width : logic$options(placementKind)) {
            int bw = logic$buttonWidth(width);
            if (mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + 17) return width;
            x += bw + 5;
        }
        return -1;
    }

    @Unique private void logic$rememberWidth(NodeKind kind, int width) {
        if (kind == NodeKind.BUS) logic$lastConnectorWidth = width;
        if (kind == NodeKind.SPLITTER) logic$lastSplitterWidth = width;
        if (kind == NodeKind.MERGER) logic$lastMergerWidth = width;
    }

    @Unique private static int[] logic$options(NodeKind kind) {
        return kind == NodeKind.BUS ? CONNECTOR_WIDTHS : PACKED_WIDTHS;
    }

    @Unique private static String logic$optionsText(NodeKind kind) {
        return kind == NodeKind.BUS ? "1 / 2 / 4 / 8 / 16 / 32 bits" : "2 / 4 / 8 / 16 / 32 bits";
    }

    @Unique private static int logic$buttonWidth(int width) {
        return width >= 10 ? 29 : 26;
    }

    @Unique private static int logic$panelWidth(int[] options) {
        int width = 14;
        for (int option : options) width += logic$buttonWidth(option) + 5;
        return width;
    }

    @Unique private static boolean logic$isRouting(NodeKind kind) {
        return kind == NodeKind.BUS || kind == NodeKind.SPLITTER || kind == NodeKind.MERGER;
    }

    @Unique private static String logic$title(NodeKind kind) {
        if (kind == NodeKind.SPLITTER) return "BUS → BITS WIDTH";
        if (kind == NodeKind.MERGER) return "BITS → BUS WIDTH";
        return "WIRE / BUS CONNECTOR WIDTH";
    }
}
