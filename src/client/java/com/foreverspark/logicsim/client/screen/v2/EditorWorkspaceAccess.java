package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;

/** Implemented by the workspace mixin so Phase 4 can remain BOARD-only. */
public interface EditorWorkspaceAccess {
    boolean logic$isBoardWorkspace();
    CircuitDocument logic$boardRootDocument();
}
