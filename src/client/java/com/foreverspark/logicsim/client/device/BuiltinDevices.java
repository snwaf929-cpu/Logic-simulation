package com.foreverspark.logicsim.client.device;

import com.foreverspark.logicsim.display.ScreenOutputDeviceDefinition;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;

/** Built-in peripherals exposed through the same port model as reusable chips. */
public final class BuiltinDevices {
    /** Internal stable ID kept as DISPLAY so older editor documents continue to load. */
    public static final String DISPLAY = ScreenOutputDeviceDefinition.ID;
    public static final String DISPLAY_LABEL = ScreenOutputDeviceDefinition.LABEL;
    public static final int DISPLAY_COLOR = ScreenOutputDeviceDefinition.COLOR;

    private static final ChipDefinition DISPLAY_DEFINITION = ScreenOutputDeviceDefinition.create();

    private BuiltinDevices() {}

    public static boolean isDisplay(String name) {
        return name != null && DISPLAY.equalsIgnoreCase(name.trim());
    }

    /** Read-only definition used by the live editor/compiler. */
    public static ChipDefinition find(String name) {
        return isDisplay(name) ? DISPLAY_DEFINITION : null;
    }

    /** Fresh copy used when the built-in is embedded into a CircuitProgram dependency graph. */
    public static ChipDefinition copy(String name) {
        return isDisplay(name) ? ScreenOutputDeviceDefinition.create() : null;
    }

    public static ChipVisualSettings displayVisual() {
        return ScreenOutputDeviceDefinition.visual();
    }
}
