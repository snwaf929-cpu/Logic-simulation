package com.foreverspark.logicsim.client.device;

import com.foreverspark.logicsim.display.ScreenOutputDeviceDefinition;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;

import java.util.List;
import java.util.Set;

/**
 * Legacy identifiers retained only so old files can be diagnosed/migrated. Phase 5 removes all fake
 * device-as-chip definitions: CPU/GPU/RAM/ROM are player-built logic, and peripherals are physical DEVICE nodes.
 */
public final class BuiltinDevices {
    public static final String DISPLAY = ScreenOutputDeviceDefinition.ID;
    public static final String DISPLAY_LABEL = ScreenOutputDeviceDefinition.LABEL;
    public static final int DISPLAY_COLOR = ScreenOutputDeviceDefinition.COLOR;

    public static final String CPU16 = "CPU16";
    public static final String RAM = "PC_RAM";
    public static final String BOOT_ROM = "BOOT_ROM";
    public static final String STORAGE = "STORAGE";
    public static final String GPU = "GPU";
    public static final String DEVICE_HOOKER = "DEVICE_HOOKER";
    public static final String IO_CONTROLLER = "IO_CONTROLLER";
    public static final String TIMER_IRQ = "TIMER_IRQ";
    public static final String PROGRAM_LOADER = "PROGRAM_LOADER";
    public static final String NETWORK_ADAPTER = "NETWORK_ADAPTER";

    private static final Set<String> REMOVED_FAKE_IDS = Set.of(
            CPU16, RAM, BOOT_ROM, STORAGE, GPU, DEVICE_HOOKER,
            IO_CONTROLLER, TIMER_IRQ, PROGRAM_LOADER, NETWORK_ADAPTER
    );

    private BuiltinDevices() {}

    /** No manually placeable built-in devices remain. */
    public static List<DeviceEntry> devices() { return List.of(); }

    public static boolean isDisplay(String name) { return name != null && DISPLAY.equalsIgnoreCase(name.trim()); }

    public static boolean isRemovedFake(String name) {
        if (name == null) return false;
        for (String id : REMOVED_FAKE_IDS) if (id.equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    /** Built-ins no longer participate in CHIP lookup. */
    public static boolean isBuiltin(String name) { return false; }
    public static ChipDefinition find(String name) { return null; }
    public static ChipDefinition copy(String name) { return null; }
    public static int color(String name) { return 0xFF7B8796; }
    public static ChipVisualSettings visual(String name) { return new ChipVisualSettings(); }
    public static ChipVisualSettings displayVisual() { return ScreenOutputDeviceDefinition.visual(); }

    public record DeviceEntry(String id, String label, int color, String badge, int badgeColor, String help) {}
}
