package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.board.ClientBoardTemplateLibrary;
import com.foreverspark.logicsim.client.screen.BoardNameScreen;
import com.foreverspark.logicsim.client.screen.BoardTemplateScreen;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.WorldBoardContextAccess;
import com.foreverspark.logicsim.client.screen.v2.BoardTemplateCanvasAccess;
import com.foreverspark.logicsim.client.screen.v2.EditorWorkspaceAccess;
import com.foreverspark.logicsim.editor.model.BoardTemplateDefinition;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.PortDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/** Phase 4 BOARD-template and socket workflow. */
@Mixin(value = CircuitEditorScreen.class, priority = 2300)
public abstract class CircuitEditorPhase4Mixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }

    @Unique private final ClientBoardTemplateLibrary logic$templates = new ClientBoardTemplateLibrary();

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$phase4Shortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (canvas == null || !canvas.active) return;
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt = (event.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        int key = event.key();
        BoardTemplateCanvasAccess phase4 = (BoardTemplateCanvasAccess)(Object)canvas;

        if (ctrl && alt && key == GLFW.GLFW_KEY_S) {
            logic$openSaveTemplateDialog();
            cir.setReturnValue(true);
            return;
        }

        if (!ctrl && !alt && key == GLFW.GLFW_KEY_T) {
            logic$openTemplateLibrary();
            cir.setReturnValue(true);
            return;
        }

        if (!ctrl && !alt && key == GLFW.GLFW_KEY_K) {
            if (!logic$isBoardWorkspace()) {
                setStatus("BOARD SOCKET: sockets belong to BOARD workspaces, not reusable CHIP definitions");
            } else {
                phase4.logic$beginSocketPlacement(shift ? PortDirection.OUTPUT : PortDirection.INPUT);
            }
            cir.setReturnValue(true);
            return;
        }

        // Socket configuration owns W before the generic Phase 2 BUS-width editor sees the same node.
        if (!ctrl && !alt && !shift && key == GLFW.GLFW_KEY_W) {
            if (phase4.logic$configureSelectedSocket((CircuitEditorScreen)(Object)this)) {
                cir.setReturnValue(true);
            }
        }
    }

    @Unique
    private void logic$openSaveTemplateDialog() {
        if (!logic$isBoardWorkspace()) {
            setStatus("BOARD TEMPLATE: Ctrl+Alt+S is available only while editing a BOARD");
            return;
        }
        CircuitEditorScreen self = (CircuitEditorScreen)(Object)this;
        Minecraft.getInstance().gui.setScreen(new BoardNameScreen(
                self,
                "SAVE BOARD TEMPLATE",
                "Reusable layout module — sockets define its external interface",
                "",
                this::logic$saveTemplateUnchecked
        ));
    }

    @Unique
    private void logic$saveTemplateUnchecked(String name) {
        try {
            CircuitDocument root = logic$boardRoot();
            logic$templates.save(name, root);
            BoardTemplateDefinition saved = logic$templates.load(name);
            setStatus("Saved BOARD template " + saved.name + " — " + saved.sockets().size() + " ordered socket"
                    + (saved.sockets().size() == 1 ? "" : "s") + "; press T to insert/replace");
        } catch (IOException exception) {
            throw new IllegalStateException(logic$message(exception), exception);
        }
    }

    @Unique
    private void logic$openTemplateLibrary() {
        if (!logic$isBoardWorkspace()) {
            setStatus("BOARD TEMPLATES are inserted/replaced only in BOARD workspaces");
            return;
        }
        CircuitEditorScreen self = (CircuitEditorScreen)(Object)this;
        BoardTemplateCanvasAccess phase4 = (BoardTemplateCanvasAccess)(Object)canvas;
        Minecraft.getInstance().gui.setScreen(new BoardTemplateScreen(
                self,
                logic$templates,
                phase4::logic$previewTemplateReplacement,
                template -> {
                    if (!phase4.logic$insertBoardTemplate(template)) throw new IllegalStateException("Template insert was rejected");
                    logic$syncPhysicalBoardRoot();
                },
                template -> {
                    if (!phase4.logic$replaceSelectedTemplate(template)) throw new IllegalStateException("Template replacement was rejected");
                    logic$syncPhysicalBoardRoot();
                }
        ));
    }

    /**
     * Insert/replace is atomic by swapping CircuitCanvasWidget to a validated candidate document.
     * A physical Circuit Block also keeps its own root reference for save/upload-on-close, so point
     * that context at the same new object before the template picker returns to the editor.
     */
    @Unique
    private void logic$syncPhysicalBoardRoot() {
        Object self = this;
        if (self instanceof WorldBoardContextAccess worldBoard) {
            worldBoard.logic$replaceWorldBoardRoot(logic$boardRoot());
        }
    }

    @Unique
    private boolean logic$isBoardWorkspace() {
        Object self = this;
        return self instanceof EditorWorkspaceAccess workspace && workspace.logic$isBoardWorkspace();
    }

    @Unique
    private CircuitDocument logic$boardRoot() {
        Object self = this;
        if (!(self instanceof EditorWorkspaceAccess workspace)) throw new IllegalStateException("BOARD workspace context is unavailable");
        return workspace.logic$boardRootDocument();
    }

    @Unique
    private static String logic$message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
