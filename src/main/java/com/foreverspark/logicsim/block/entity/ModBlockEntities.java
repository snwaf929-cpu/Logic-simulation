package com.foreverspark.logicsim.block.entity;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.ModBlocks;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
    public static final BlockEntityType<DisplayPanelBlockEntity> DISPLAY_PANEL = register(
            "display_panel",
            DisplayPanelBlockEntity::new,
            ModBlocks.DISPLAY_PANEL
    );

    private ModBlockEntities() {}

    private static <T extends BlockEntity> BlockEntityType<T> register(
            String name,
            FabricBlockEntityTypeBuilder.Factory<? extends T> factory,
            Block... blocks
    ) {
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, name);
        return Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id,
                FabricBlockEntityTypeBuilder.<T>create(factory, blocks).build()
        );
    }

    public static void initialize() {
        // Class loading performs registration.
    }
}
