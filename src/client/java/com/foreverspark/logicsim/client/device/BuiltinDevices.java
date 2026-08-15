package com.foreverspark.logicsim.client.device;

import com.foreverspark.logicsim.display.ScreenOutputDeviceDefinition;
import com.foreverspark.logicsim.editor.model.ChipDefinition;
import com.foreverspark.logicsim.editor.model.ChipVisualSettings;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.util.List;
import java.util.function.Supplier;

/** Built-in peripherals exposed through the same port model as reusable chips. */
public final class BuiltinDevices {
    /** Internal stable ID kept as DISPLAY so older editor documents continue to load. */
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

    private static final ChipVisualSettings STANDARD_VISUAL = new ChipVisualSettings(220.0, 150.0, 18.0);
    private static final ChipVisualSettings LARGE_VISUAL = new ChipVisualSettings(248.0, 184.0, 18.0);

    private static final List<RegisteredDevice> REGISTERED = List.of(
            device(CPU16, "16-BIT CPU", 0xFF5FA8FF, "CORE", 0xFF9BCBFF,
                    "CPU interface: CLOCK/RESET/DATA_IN/IRQ -> ADDR/DATA_OUT/READ/WRITE/HALT.",
                    () -> createStub(CPU16, 0xFF5FA8FF, LARGE_VISUAL,
                            inputs(port("CLOCK", 1), port("RESET", 1), port("DATA_IN", 16), port("IRQ", 8)),
                            outputs(port("ADDR", 32), port("DATA_OUT", 16), port("READ", 1), port("WRITE", 1), port("HALT", 1)))),
            device(RAM, "RAM", 0xFF55B96B, "MEM16", 0xFF91D79F,
                    "16-bit memory device on the 32-bit address bus.",
                    () -> createStub(RAM, 0xFF55B96B, STANDARD_VISUAL,
                            inputs(port("ADDR", 32), port("DATA_IN", 16), port("READ", 1), port("WRITE", 1)),
                            outputs(port("DATA_OUT", 16), port("READY", 1)))),
            device(BOOT_ROM, "ROM / BIOS", 0xFFD29A45, "BOOT", 0xFFF0C77D,
                    "Read-only boot firmware device used to start the machine.",
                    () -> createStub(BOOT_ROM, 0xFFD29A45, STANDARD_VISUAL,
                            inputs(port("ADDR", 32), port("READ", 1)),
                            outputs(port("DATA_OUT", 16), port("READY", 1)))),
            device(STORAGE, "STORAGE / SSD", 0xFF9B72CF, "DISK", 0xFFC5A7E8,
                    "Persistent mass-storage interface for programs, games, and an OS.",
                    () -> createStub(STORAGE, 0xFF9B72CF, STANDARD_VISUAL,
                            inputs(port("ADDR", 32), port("DATA_IN", 16), port("READ", 1), port("WRITE", 1)),
                            outputs(port("DATA_OUT", 16), port("READY", 1), port("IRQ", 1)))),
            device(GPU, "GPU", 0xFFE05252, "GPU", 0xFFFFA3A3,
                    "Graphics processor interface. SCREEN_DATA[64] will drive the display pipeline.",
                    () -> createStub(GPU, 0xFFE05252, LARGE_VISUAL,
                            inputs(port("ADDR", 32), port("DATA_IN", 16), port("READ", 1), port("WRITE", 1)),
                            outputs(port("DATA_OUT", 16), port("READY", 1), port("IRQ", 1), port("SCREEN_DATA", 64)))),
            device(DEVICE_HOOKER, "DEVICE HOOKER", 0xFF35B8C8, "F4 RELEASE", 0xFF8FE4EC,
                    "Captures the real keyboard and mouse for the simulated PC; F4 will release capture.",
                    () -> createStub(DEVICE_HOOKER, 0xFF35B8C8, LARGE_VISUAL,
                            inputs(port("CAPTURE", 1), port("RELEASE", 1)),
                            outputs(port("KEY_CODE", 16), port("KEY_STATE", 16), port("MOUSE_X", 16), port("MOUSE_Y", 16),
                                    port("MOUSE_BUTTONS", 8), port("WHEEL", 8), port("IRQ", 1)))),
            device(IO_CONTROLLER, "I/O CONTROLLER", 0xFF4FA6A0, "BUS", 0xFF8DD6D1,
                    "Routes memory-mapped I/O and device interrupt lines.",
                    () -> createStub(IO_CONTROLLER, 0xFF4FA6A0, LARGE_VISUAL,
                            inputs(port("ADDR", 32), port("DATA_IN", 16), port("READ", 1), port("WRITE", 1), port("IRQ_IN", 8)),
                            outputs(port("DATA_OUT", 16), port("READY", 1), port("IRQ_OUT", 1)))),
            device(TIMER_IRQ, "TIMER / IRQ", 0xFFF06AAE, "IRQ", 0xFFFFADD2,
                    "Hardware timer and interrupt controller for the CPU.",
                    () -> createStub(TIMER_IRQ, 0xFFF06AAE, STANDARD_VISUAL,
                            inputs(port("CLOCK", 1), port("RESET", 1), port("ENABLE", 1), port("PERIOD", 32), port("ACK", 8)),
                            outputs(port("IRQ", 8), port("TICKS", 32)))),
            device(PROGRAM_LOADER, "PROGRAM LOADER", 0xFF7B8796, "HOST", 0xFFB9C2CC,
                    "Host bridge used to load assembled binaries into the simulated computer.",
                    () -> createStub(PROGRAM_LOADER, 0xFF7B8796, STANDARD_VISUAL,
                            inputs(port("START", 1), port("ADDR", 32), port("DATA_IN", 16)),
                            outputs(port("DATA_OUT", 16), port("WRITE", 1), port("BUSY", 1), port("DONE", 1)))),
            device(NETWORK_ADAPTER, "NETWORK ADAPTER", 0xFF63A9D8, "NET", 0xFFA8D8F3,
                    "Network bridge for the simulated PC. External networking remains disabled until its runtime is implemented.",
                    () -> createStub(NETWORK_ADAPTER, 0xFF63A9D8, STANDARD_VISUAL,
                            inputs(port("ADDR", 32), port("DATA_IN", 16), port("READ", 1), port("WRITE", 1)),
                            outputs(port("DATA_OUT", 16), port("READY", 1), port("IRQ", 1)))),
            device(DISPLAY, DISPLAY_LABEL, DISPLAY_COLOR, "OUT64", 0xFF9ADDE8,
                    "X/Y pixel position, RGB565 color, DRAW/CLEAR controls, and automatic DATA[64] packing.",
                    ScreenOutputDeviceDefinition::create)
    );

    private static final List<DeviceEntry> ENTRIES = REGISTERED.stream().map(RegisteredDevice::entry).toList();

    private BuiltinDevices() {}

    public static List<DeviceEntry> devices() {
        return ENTRIES;
    }

    public static boolean isDisplay(String name) {
        return matches(name, DISPLAY);
    }

    public static boolean isBuiltin(String name) {
        return findRegistered(name) != null;
    }

    /** Read-only definition used by the live editor/compiler. */
    public static ChipDefinition find(String name) {
        RegisteredDevice device = findRegistered(name);
        return device == null ? null : device.cachedDefinition();
    }

    /** Fresh copy used when the built-in is embedded into a CircuitProgram dependency graph. */
    public static ChipDefinition copy(String name) {
        RegisteredDevice device = findRegistered(name);
        return device == null ? null : device.factory().get();
    }

    public static int color(String name) {
        RegisteredDevice device = findRegistered(name);
        return device == null ? 0xFF7B8796 : device.entry().color();
    }

    public static ChipVisualSettings visual(String name) {
        RegisteredDevice device = findRegistered(name);
        if (device == null) return new ChipVisualSettings();
        if (isDisplay(name)) return ScreenOutputDeviceDefinition.visual();
        return copyVisual(device.visual());
    }

    public static ChipVisualSettings displayVisual() {
        return ScreenOutputDeviceDefinition.visual();
    }

    private static RegisteredDevice findRegistered(String name) {
        if (name == null) return null;
        String normalized = name.trim();
        for (RegisteredDevice device : REGISTERED) {
            if (device.entry().id().equalsIgnoreCase(normalized)) return device;
        }
        return null;
    }

    private static boolean matches(String name, String id) {
        return name != null && id.equalsIgnoreCase(name.trim());
    }

    private static RegisteredDevice device(String id, String label, int color, String badge, int badgeColor,
                                           String help, Supplier<ChipDefinition> factory) {
        DeviceEntry entry = new DeviceEntry(id, label, color, badge, badgeColor, help);
        ChipDefinition cached = factory.get();
        ChipVisualSettings visual = isScreenId(id) ? ScreenOutputDeviceDefinition.visual() : copyVisual(cached.visual);
        return new RegisteredDevice(entry, factory, cached, visual);
    }

    private static boolean isScreenId(String id) {
        return DISPLAY.equalsIgnoreCase(id);
    }

    private static ChipDefinition createStub(String id, int color, ChipVisualSettings visual,
                                             List<PortDef> inputPorts, List<PortDef> outputPorts) {
        CircuitDocument circuit = new CircuitDocument();
        double inputY = 0.0;
        for (PortDef port : inputPorts) {
            EditorNode input = circuit.addNode(NodeKind.INPUT, 0.0, inputY);
            input.label = port.label();
            input.width = port.width();
            inputY += 36.0;
        }

        double outputY = 0.0;
        for (PortDef port : outputPorts) {
            EditorNode low = circuit.addNode(NodeKind.CONSTANT, 180.0, outputY);
            low.label = port.label() + " STUB";
            low.width = port.width();
            low.constantValue = 0L;

            EditorNode output = circuit.addNode(NodeKind.OUTPUT, 340.0, outputY);
            output.label = port.label();
            output.width = port.width();
            circuit.connect(low.id, 0, output.id, 0);
            outputY += 36.0;
        }

        ChipDefinition definition = new ChipDefinition(id, circuit, copyVisual(visual));
        definition.color = color;
        return definition;
    }

    private static ChipVisualSettings copyVisual(ChipVisualSettings source) {
        if (source == null) return new ChipVisualSettings();
        return new ChipVisualSettings(source.width, source.minHeight, source.portSpacing);
    }

    private static PortDef port(String label, int width) {
        return new PortDef(label, width);
    }

    @SafeVarargs
    private static <T> List<T> inputs(T... ports) {
        return List.of(ports);
    }

    @SafeVarargs
    private static <T> List<T> outputs(T... ports) {
        return List.of(ports);
    }

    public record DeviceEntry(String id, String label, int color, String badge, int badgeColor, String help) {}

    private record PortDef(String label, int width) {}

    private record RegisteredDevice(DeviceEntry entry, Supplier<ChipDefinition> factory,
                                    ChipDefinition cachedDefinition, ChipVisualSettings visual) {}
}
