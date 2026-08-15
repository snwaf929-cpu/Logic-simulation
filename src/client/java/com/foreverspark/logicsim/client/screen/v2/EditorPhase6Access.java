package com.foreverspark.logicsim.client.screen.v2;

/** Canvas actions used by the Phase 6 editor shortcuts/context UI. */
public interface EditorPhase6Access {
    boolean logic$toggleSelectedLocks();
    boolean logic$alignSelected(EditorLayoutTools.Alignment alignment);
    boolean logic$alignSelectedPinRows();
    boolean logic$distributeSelected(EditorLayoutTools.Axis axis);
}
