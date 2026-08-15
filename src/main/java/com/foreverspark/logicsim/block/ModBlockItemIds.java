package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;

public final class ModBlockItemIds {
    public static final BlockItemId CIRCUIT_BLOCK = create("circuit_block");
    public static final BlockItemId SIGNAL_WIRE = create("signal_wire");
    public static final BlockItemId BUS_CABLE_2 = create("bus_cable_2");
    public static final BlockItemId BUS_CABLE_4 = create("bus_cable_4");
    public static final BlockItemId BUS_CABLE = create("bus_cable");
    public static final BlockItemId BUS_CABLE_16 = create("bus_cable_16");
    public static final BlockItemId BUS_CABLE_32 = create("bus_cable_32");
    public static final BlockItemId BUS_CABLE_64 = create("bus_cable_64");
    public static final BlockItemId DISPLAY_BLOCK = create("display_block");
    public static final BlockItemId IO_CONNECTOR = create("io_connector");
    public static final BlockItemId USER_INPUT_BRIDGE = create("user_input_bridge");
    public static final BlockItemId INTERNET_DEVICE = create("internet_device");
    public static final BlockItemId STORAGE_DEVICE = create("storage_device");

    private ModBlockItemIds() {}

    private static BlockItemId create(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, name);
        return BlockItemId.create(id, id);
    }
}
