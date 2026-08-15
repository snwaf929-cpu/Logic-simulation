package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.board.ClientBoardLibrary;
import com.foreverspark.logicsim.client.screen.BoardNameScreen;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.CircuitEditorScreen;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.EditorWorkspaceRuntime;
import com.foreverspark.logicsim.client.screen.WorldBoardContextAccess;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds first-class editable BOARD projects without changing reusable CHIP semantics.
 *
 * Ctrl+S on a top-level board saves a board project. Ctrl+Shift+S intentionally keeps the old
 * "save as reusable chip" flow. Opening a board/chip pushes the current root workspace so the normal
 * Back button / Alt+Left returns to the exact document being edited before the switch.
 */
@Mixin(value = CircuitEditorScreen.class, priority = 1500)
public abstract class CircuitEditorWorkspaceMixin {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private ComponentLibraryWidget componentLibrary;
    @Shadow private String currentChipName;
    @Shadow private void setStatus(String status) { throw new AssertionError(); }
    @Shadow private void openSaveModal() { throw new AssertionError(); }

    @Unique private final ClientBoardLibrary logic$boards = new ClientBoardLibrary();
    @Unique private final List<WorkspaceFrame> logic$workspaceHistory = new ArrayList<>();
    @Unique private String logic$currentBoardName;
    @Unique private String logic$rootChipName;
    @Unique private boolean logic$forceChipSave;

    @Inject(method = "init", at = @At("RETURN"))
    private void logic$installWorkspaceHooks(CallbackInfo ci) {
        if (canvas == null || componentLibrary == null) return;
        componentLibrary.setBoardOpenHandler(this::logic$openBoard);
        EditorWorkspaceRuntime.register(canvas, new EditorWorkspaceRuntime.Handler() {
            @Override public boolean canGoBack() { return !logic$workspaceHistory.isEmpty(); }
            @Override public boolean goBack() { return logic$restorePreviousWorkspace(); }
        });
        if (!canvas.isNestedView() && logic$currentBoardName == null && currentChipName != null && !currentChipName.isBlank()) {
            logic$rootChipName = currentChipName;
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void logic$unregisterWorkspaceHooks(CallbackInfo ci) {
        if (canvas != null) EditorWorkspaceRuntime.unregister(canvas);
    }

    @Inject(method = "newCircuit", at = @At("HEAD"))
    private void logic$captureBeforeNewBoard(CallbackInfo ci) {
        logic$pushCurrentWorkspace();
    }

    @Inject(method = "newCircuit", at = @At("RETURN"))
    private void logic$markNewBoard(CallbackInfo ci) {
        logic$currentBoardName = null;
        logic$rootChipName = null;
        if (componentLibrary != null) componentLibrary.selectBoard(null);
        setStatus("New editable BOARD — Ctrl+S saves it under BOARDS; Ctrl+Shift+S makes a reusable CHIP");
    }

    @Inject(method = "openChip", at = @At("HEAD"))
    private void logic$captureBeforeOpenChip(String name, CallbackInfo ci) {
        if (name == null || name.isBlank()) return;
        if (!canvas.isNestedView() && logic$currentBoardName == null && logic$rootChipName != null
                && logic$rootChipName.equalsIgnoreCase(name)) return;
        logic$pushCurrentWorkspace();
    }

    @Inject(method = "openChip", at = @At("RETURN"))
    private void logic$markStandaloneChip(String name, CallbackInfo ci) {
        if (currentChipName == null || currentChipName.isBlank()) return;
        logic$currentBoardName = null;
        logic$rootChipName = currentChipName;
        setStatus("Editing CHIP " + currentChipName + " — Ctrl+S saves it; Back returns to the previous board/chip");
    }

    /** Ctrl+S is context-sensitive: BOARD saves as a board project, CHIP keeps the existing chip save flow. */
    @Inject(method = "openSaveModal", at = @At("HEAD"), cancellable = true)
    private void logic$saveBoardInsteadOfChip(CallbackInfo ci) {
        if (logic$forceChipSave) {
            logic$forceChipSave = false;
            return;
        }
        if (canvas == null || canvas.isNestedView() || currentChipName != null) return;

        if (logic$currentBoardName != null && !logic$currentBoardName.isBlank()) {
            try {
                logic$boards.save(logic$currentBoardName, logic$rootDocument());
                if (componentLibrary != null) componentLibrary.selectBoard(logic$currentBoardName);
                setStatus("Saved BOARD " + logic$currentBoardName + " — it stays editable and can be reopened from BOARDS");
            } catch (IOException | RuntimeException exception) {
                setStatus("BOARD SAVE FAILED: " + logic$message(exception));
            }
            ci.cancel();
            return;
        }

        CircuitEditorScreen self = (CircuitEditorScreen)(Object)this;
        Minecraft.getInstance().gui.setScreen(new BoardNameScreen(
                self,
                "SAVE BOARD",
                "Editable project — this does not convert the board into a chip",
                "",
                this::logic$saveBoardAsUnchecked
        ));
        ci.cancel();
    }

    /** Ctrl+Shift+S explicitly means "make/save a reusable chip" while Ctrl+S remains normal project save. */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void logic$workspaceShortcuts(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctrl && shift && event.key() == GLFW.GLFW_KEY_S) {
            logic$forceChipSave = true;
            openSaveModal();
            cir.setReturnValue(true);
        }
    }

    /** F2 on a selected board renames the editable project instead of entering the chip/folder modal. */
    @Inject(method = "openF2EditModal", at = @At("HEAD"), cancellable = true)
    private void logic$renameSelectedBoard(CallbackInfo ci) {
        if (componentLibrary == null) return;
        String selected = componentLibrary.selectedBoardName();
        if (selected == null || selected.isBlank()) return;

        CircuitEditorScreen self = (CircuitEditorScreen)(Object)this;
        Minecraft.getInstance().gui.setScreen(new BoardNameScreen(
                self,
                "RENAME BOARD",
                "Rename the saved editable board project",
                selected,
                newName -> logic$renameBoardUnchecked(selected, newName)
        ));
        ci.cancel();
    }

    @Unique
    private void logic$openBoard(String name) {
        if (name == null || name.isBlank()) return;
        try {
            // Load first so a corrupt/missing board does not add a bogus Back-history entry.
            CircuitDocument board = logic$boards.load(name);
            logic$pushCurrentWorkspace();
            canvas.setDocument(board, null);
            ((WorldBoardContextAccess)(Object)this).logic$replaceWorldBoardRoot(board);
            currentChipName = null;
            logic$currentBoardName = name;
            logic$rootChipName = null;
            componentLibrary.selectBoard(name);
            setStatus("Opened editable BOARD " + name + " — Ctrl+S saves changes; double-click a chip to edit it, Back returns here");
        } catch (IOException | RuntimeException exception) {
            setStatus("BOARD OPEN FAILED: " + logic$message(exception));
        }
    }

    @Unique
    private void logic$saveBoardAsUnchecked(String name) {
        try {
            logic$boards.save(name, logic$rootDocument());
            logic$currentBoardName = name;
            logic$rootChipName = null;
            if (componentLibrary != null) componentLibrary.selectBoard(name);
            setStatus("Saved editable BOARD " + name + " — reopen it anytime from the BOARDS section");
        } catch (IOException exception) {
            throw new IllegalStateException(logic$message(exception), exception);
        }
    }

    @Unique
    private void logic$renameBoardUnchecked(String oldName, String newName) {
        try {
            logic$boards.rename(oldName, newName);
            if (oldName.equals(logic$currentBoardName)) logic$currentBoardName = newName;
            if (componentLibrary != null) {
                componentLibrary.renameBoardSelection(oldName, newName);
                componentLibrary.selectBoard(newName);
            }
            setStatus("Renamed BOARD " + oldName + " -> " + newName);
        } catch (IOException exception) {
            throw new IllegalStateException(logic$message(exception), exception);
        }
    }

    @Unique
    private void logic$pushCurrentWorkspace() {
        if (canvas == null) return;
        CircuitDocument root = logic$rootDocument();
        if (root == null) return;
        logic$workspaceHistory.add(new WorkspaceFrame(
                logic$boards.copyDocument(root),
                logic$currentBoardName,
                logic$currentBoardName == null ? logic$rootChipName : null
        ));
        while (logic$workspaceHistory.size() > 16) logic$workspaceHistory.removeFirst();
    }

    @Unique
    private boolean logic$restorePreviousWorkspace() {
        if (logic$workspaceHistory.isEmpty() || canvas == null) return false;
        WorkspaceFrame frame = logic$workspaceHistory.removeLast();
        CircuitDocument restored = logic$boards.copyDocument(frame.document());
        canvas.setDocument(restored, frame.chipName());
        if (frame.chipName() == null) {
            // Named and unsaved boards are both physical-board roots. Standalone chip workspaces are not.
            ((WorldBoardContextAccess)(Object)this).logic$replaceWorldBoardRoot(restored);
        }
        currentChipName = frame.chipName();
        logic$currentBoardName = frame.boardName();
        logic$rootChipName = frame.chipName();

        if (componentLibrary != null) {
            if (frame.boardName() != null) componentLibrary.selectBoard(frame.boardName());
            else if (frame.chipName() != null) componentLibrary.selectChip(frame.chipName());
            else componentLibrary.selectBoard(null);
        }
        setStatus(frame.boardName() != null
                ? "Back to editable BOARD " + frame.boardName()
                : frame.chipName() != null ? "Back to CHIP " + frame.chipName() : "Back to unsaved BOARD");
        return true;
    }

    @Unique
    private CircuitDocument logic$rootDocument() {
        if (canvas == null) return new CircuitDocument();
        try {
            CircuitDocument root = ((CanvasAccess)(Object)canvas).logic$getRuntimeRootDocument();
            return root == null ? canvas.document() : root;
        } catch (RuntimeException ignored) {
            return canvas.document();
        }
    }

    @Unique
    private static String logic$message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Unique
    private record WorkspaceFrame(CircuitDocument document, String boardName, String chipName) {}
}
