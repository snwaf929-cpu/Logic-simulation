package com.foreverspark.logicsim.client.screen;

import net.minecraft.client.gui.screens.Screen;

/** Tracks the editor screen that owns canvas/library pop-up configuration screens. */
public final class EditorScreenContext {
    private static Screen editor;

    private EditorScreenContext() {}

    public static void set(Screen screen) { editor = screen; }
    public static Screen current() { return editor; }
}
