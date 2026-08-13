package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static final BlockEntityType<DisplayBlockEntity> DISPLAY = register("display", DisplayBlockEntity::new, DisplayBlocks.DISPLAY_BLOCK);
    public static final BlockEntityType<CircuitPortBlockEntity> CIRCUIT_PORT = register("circuit_port", CircuitPortBlockEntity::new, ConnectorBlocks.IO_CONNECTOR);

    private ModBlockEntities() {}

    private static <T extends BlockEntity> BlockEntityType<T> register(String name, FabricBlockEntityTypeBuilder.Factory<? extends T> factory, Block... blocks) {
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, name);
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, FabricBlockEntityTypeBuilder.<T>create(factory, blocks).build());
    }

    public static void initialize() {}
}
