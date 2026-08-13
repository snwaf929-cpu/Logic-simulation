package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.interconnect.CableKind;
import java.util.function.Function;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
    public static final Block CIRCUIT_BLOCK = register(
            ModBlockItemIds.CIRCUIT_BLOCK,
            CircuitBlock::new,
            BlockBehaviour.Properties.of().sound(SoundType.METAL)
    );

    public static final CableBlock SIGNAL_WIRE = (CableBlock) register(
            ModBlockItemIds.SIGNAL_WIRE,
            properties -> new CableBlock(CableKind.SIGNAL, properties),
            BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion()
    );

    public static final CableBlock BUS_CABLE = (CableBlock) register(
            ModBlockItemIds.BUS_CABLE,
            properties -> new CableBlock(CableKind.BUS, properties),
            BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion()
    );

    public static final Block DISPLAY_PANEL = register(
            ModBlockItemIds.DISPLAY_PANEL,
            DisplayPanelBlock::new,
            BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion()
    );

    private ModBlocks() {
    }

    private static Block register(
            ResourceKey<Block> id,
            Function<BlockBehaviour.Properties, Block> blockFactory,
            BlockBehaviour.Properties properties
    ) {
        Block block = blockFactory.apply(properties.setId(id));
        return Registry.register(BuiltInRegistries.BLOCK, id, block);
    }

    private static Block register(
            BlockItemId id,
            Function<BlockBehaviour.Properties, Block> blockFactory,
            BlockBehaviour.Properties properties
    ) {
        Block block = register(id.block(), blockFactory, properties);
        BlockItem blockItem = new BlockItem(
                block,
                new Item.Properties().useBlockDescriptionPrefix().setId(id.item())
        );
        Registry.register(BuiltInRegistries.ITEM, id.item(), blockItem);
        return block;
    }

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(tab -> {
            tab.accept(CIRCUIT_BLOCK.asItem());
            tab.accept(SIGNAL_WIRE.asItem());
            tab.accept(BUS_CABLE.asItem());
            tab.accept(DISPLAY_PANEL.asItem());
        });
    }
}
