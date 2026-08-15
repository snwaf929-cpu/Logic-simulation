package com.foreverspark.logicsim.editor.model;

/** Presentation-only PCB copper side for editor routing. */
public enum WireLayer {
    FRONT,
    BACK;

    public WireLayer opposite() {
        return this == FRONT ? BACK : FRONT;
    }
}
