package com.foreverspark.logicsim.client.screen;

import com.foreverspark.logicsim.editor.model.CircuitDocument;

/** Internal bridge used when a saved editable board project is loaded into a physical Circuit Block editor. */
public interface WorldBoardContextAccess {
    void logic$replaceWorldBoardRoot(CircuitDocument root);
}
