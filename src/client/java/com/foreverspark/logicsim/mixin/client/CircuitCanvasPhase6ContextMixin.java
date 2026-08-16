package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CanvasConfigAccess;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.EditorScreenContext;
import com.foreverspark.logicsim.client.screen.v2.BoardTemplateCanvasAccess;
import com.foreverspark.logicsim.client.screen.v2.CanvasPhase2ConfigAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorLayoutTools;
import com.foreverspark.logicsim.client.screen.v2.EditorPhase6Access;
import com.foreverspark.logicsim.editor.model.EditorNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Shift+RMB CAD context menu. Plain RMB/MMB remain dedicated to canvas panning. */
@Mixin(value = CircuitCanvasWidget.class, priority = 2200)
public abstract class CircuitCanvasPhase6ContextMixin {
    @Unique private static final int LOGIC_MENU_WIDTH = 142;
    @Unique private static final int LOGIC_MENU_ROW_HEIGHT = 17;
    @Unique private static final int LOGIC_MENU_HEADER_HEIGHT = 18;
    @Unique private static final List<LogicContextAction> LOGIC_ACTIONS = List.of(
            new LogicContextAction("EDIT / PROPERTIES", LogicContextKind.EDIT),
            new LogicContextAction("LOCK / UNLOCK", LogicContextKind.LOCK),
            new LogicContextAction("DUPLICATE", LogicContextKind.DUPLICATE),
            new LogicContextAction("ALIGN LEFT", LogicContextKind.ALIGN_LEFT),
            new LogicContextAction("ALIGN RIGHT", LogicContextKind.ALIGN_RIGHT),
            new LogicContextAction("ALIGN TOP", LogicContextKind.ALIGN_TOP),
            new LogicContextAction("ALIGN BOTTOM", LogicContextKind.ALIGN_BOTTOM),
            new LogicContextAction("ALIGN PIN ROWS", LogicContextKind.ALIGN_PINS),
            new LogicContextAction("DISTRIBUTE H", LogicContextKind.DISTRIBUTE_H),
            new LogicContextAction("DISTRIBUTE V", LogicContextKind.DISTRIBUTE_V)
    );

    @Shadow @Final private LinkedHashSet<Integer> selectedNodeIds;
    @Shadow @Final private Consumer<String> status;
    @Shadow private EditorNode nodeAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow private void selectSingleNode(int nodeId) { throw new AssertionError(); }
    @Shadow public abstract boolean duplicateSelection();

    @Unique private boolean logic$contextOpen;
    @Unique private int logic$contextX;
    @Unique private int logic$contextY;

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$phase6ContextClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        CircuitCanvasWidget self = (CircuitCanvasWidget)(Object)this;
        if (!logic$inside(self, event.x(), event.y())) return;

        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (event.button() == 1 && shift) {
            EditorNode node = nodeAt(event.x(), event.y());
            if (node != null && !selectedNodeIds.contains(node.id)) selectSingleNode(node.id);
            if (selectedNodeIds.isEmpty()) {
                status.accept("CONTEXT: select one or more components first");
                ci.cancel();
                return;
            }
            logic$openContext(self, event.x(), event.y());
            status.accept("CONTEXT: edit properties, layout, duplicate, or lock the current component selection");
            ci.cancel();
            return;
        }

        if (!logic$contextOpen) return;
        if (event.button() == 0) {
            LogicContextAction action = logic$actionAt(event.x(), event.y());
            logic$contextOpen = false;
            if (action != null) {
                logic$runContextAction(action.kind());
                ci.cancel();
            }
            return;
        }
        logic$contextOpen = false;
    }

    @Inject(method = "cancelTransientMode", at = @At("HEAD"), cancellable = true)
    private void logic$phase6CloseContext(CallbackInfoReturnable<Boolean> cir) {
        if (!logic$contextOpen) return;
        logic$contextOpen = false;
        status.accept("Context menu closed");
        cir.setReturnValue(true);
    }

    @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
    private void logic$phase6ContextRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!logic$contextOpen) return;
        int height = LOGIC_MENU_HEADER_HEIGHT + LOGIC_ACTIONS.size() * LOGIC_MENU_ROW_HEIGHT + 2;
        graphics.fill(logic$contextX, logic$contextY, logic$contextX + LOGIC_MENU_WIDTH, logic$contextY + height, 0xF5161B21);
        graphics.outline(logic$contextX, logic$contextY, LOGIC_MENU_WIDTH, height, 0xFF596674);
        graphics.fill(logic$contextX, logic$contextY, logic$contextX + 3, logic$contextY + height, 0xFF63A9D8);
        graphics.text(Minecraft.getInstance().font, "SELECTION", logic$contextX + 9, logic$contextY + 6, 0xFF8C99A7, false);

        for (int i = 0; i < LOGIC_ACTIONS.size(); i++) {
            int y = logic$contextY + LOGIC_MENU_HEADER_HEIGHT + i * LOGIC_MENU_ROW_HEIGHT;
            boolean hovered = mouseX >= logic$contextX + 3 && mouseX < logic$contextX + LOGIC_MENU_WIDTH
                    && mouseY >= y && mouseY < y + LOGIC_MENU_ROW_HEIGHT;
            if (hovered) graphics.fill(logic$contextX + 3, y, logic$contextX + LOGIC_MENU_WIDTH - 1, y + LOGIC_MENU_ROW_HEIGHT, 0xFF283440);
            graphics.text(Minecraft.getInstance().font, LOGIC_ACTIONS.get(i).label(), logic$contextX + 10, y + 5,
                    hovered ? 0xFFFFFFFF : 0xFFD0D7DF, false);
        }
    }

    @Unique
    private void logic$openContext(CircuitCanvasWidget self, double mouseX, double mouseY) {
        int menuHeight = LOGIC_MENU_HEADER_HEIGHT + LOGIC_ACTIONS.size() * LOGIC_MENU_ROW_HEIGHT + 2;
        int minX = self.getX() + 2;
        int maxX = self.getX() + self.getWidth() - LOGIC_MENU_WIDTH - 2;
        int minY = self.getY() + 2;
        int maxY = self.getY() + self.getHeight() - menuHeight - 2;
        logic$contextX = Math.max(minX, Math.min((int)Math.round(mouseX), Math.max(minX, maxX)));
        logic$contextY = Math.max(minY, Math.min((int)Math.round(mouseY), Math.max(minY, maxY)));
        logic$contextOpen = true;
    }

    @Unique
    private LogicContextAction logic$actionAt(double mouseX, double mouseY) {
        if (mouseX < logic$contextX || mouseX >= logic$contextX + LOGIC_MENU_WIDTH) return null;
        int localY = (int)Math.floor(mouseY) - logic$contextY - LOGIC_MENU_HEADER_HEIGHT;
        if (localY < 0) return null;
        int index = localY / LOGIC_MENU_ROW_HEIGHT;
        if (index < 0 || index >= LOGIC_ACTIONS.size()) return null;
        return LOGIC_ACTIONS.get(index);
    }

    @Unique
    private void logic$runContextAction(LogicContextKind kind) {
        EditorPhase6Access phase6 = (EditorPhase6Access)(Object)this;
        switch (kind) {
            case EDIT -> logic$editProperties();
            case LOCK -> phase6.logic$toggleSelectedLocks();
            case DUPLICATE -> duplicateSelection();
            case ALIGN_LEFT -> phase6.logic$alignSelected(EditorLayoutTools.Alignment.LEFT);
            case ALIGN_RIGHT -> phase6.logic$alignSelected(EditorLayoutTools.Alignment.RIGHT);
            case ALIGN_TOP -> phase6.logic$alignSelected(EditorLayoutTools.Alignment.TOP);
            case ALIGN_BOTTOM -> phase6.logic$alignSelected(EditorLayoutTools.Alignment.BOTTOM);
            case ALIGN_PINS -> phase6.logic$alignSelectedPinRows();
            case DISTRIBUTE_H -> phase6.logic$distributeSelected(EditorLayoutTools.Axis.HORIZONTAL);
            case DISTRIBUTE_V -> phase6.logic$distributeSelected(EditorLayoutTools.Axis.VERTICAL);
        }
    }

    @Unique
    private void logic$editProperties() {
        Screen parent = EditorScreenContext.current();
        Object self = this;

        // CLOCK/RANDOM have their own typed source editor and support same-type multi-selection batch editing.
        if (self instanceof CanvasConfigAccess source && source.logic$editSelectedSources(parent)) return;
        // Authored BOARD SOCKET metadata has a dedicated identity/direction/width/order dialog.
        if (self instanceof BoardTemplateCanvasAccess board && board.logic$configureSelectedSocket(parent)) return;
        // General widths/values/BUS_SLICE/NET_LABEL use the exact Phase-2 properties dialogs.
        if (self instanceof CanvasPhase2ConfigAccess config && config.logic$configureSelected(parent)) return;
        status.accept(selectedNodeIds.size() == 1
                ? "Selected component has no editable properties"
                : "EDIT / PROPERTIES requires one component, except CLOCK/RANDOM batch editing");
    }

    @Unique
    private static boolean logic$inside(CircuitCanvasWidget self, double x, double y) {
        return x >= self.getX() && x < self.getX() + self.getWidth() && y >= self.getY() && y < self.getY() + self.getHeight();
    }

    @Unique private enum LogicContextKind {
        EDIT, LOCK, DUPLICATE, ALIGN_LEFT, ALIGN_RIGHT, ALIGN_TOP, ALIGN_BOTTOM, ALIGN_PINS, DISTRIBUTE_H, DISTRIBUTE_V
    }

    @Unique private record LogicContextAction(String label, LogicContextKind kind) {}
}
