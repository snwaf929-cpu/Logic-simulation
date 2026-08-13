package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;

public final class ModBlockItemIds {
    public static final BlockItemId CIRCUIT_BLOCK = create("circuit_block");
    public static final BlockItemId SIGNAL_WIRE = create("signal_wire");
    public static final BlockItemId BUS_CABLE = create("bus_cable");
    public static final BlockItemId DISPLAY_PANEL = create("display_panel");

    private ModBlockItemIds() {
    }

    private static BlockItemId create(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, name);
        return BlockItemId.create(id, id);
    }
}
