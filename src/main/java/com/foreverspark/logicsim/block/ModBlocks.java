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
            ProgrammableCircuitBlock::new,
            BlockBehaviour.Properties.of().sound(SoundType.METAL)
    );

    public static final CableBlock SIGNAL_WIRE = (CableBlock) register(
            ModBlockItemIds.SIGNAL_WIRE,
            properties -> new CableBlock(CableKind.SIGNAL, 1, properties),
            BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion()
    );

    public static final CableBlock BUS_CABLE_2 = registerBus(ModBlockItemIds.BUS_CABLE_2, 2);
    public static final CableBlock BUS_CABLE_4 = registerBus(ModBlockItemIds.BUS_CABLE_4, 4);
    public static final CableBlock BUS_CABLE = registerBus(ModBlockItemIds.BUS_CABLE, 8);
    public static final CableBlock BUS_CABLE_16 = registerBus(ModBlockItemIds.BUS_CABLE_16, 16);
    public static final CableBlock BUS_CABLE_32 = registerBus(ModBlockItemIds.BUS_CABLE_32, 32);

    private ModBlocks() {}

    private static CableBlock registerBus(BlockItemId id, int width) {
        return (CableBlock) register(id, properties -> new CableBlock(CableKind.BUS, width, properties), BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion());
    }

    private static Block register(ResourceKey<Block> id, Function<BlockBehaviour.Properties, Block> blockFactory, BlockBehaviour.Properties properties) {
        Block block = blockFactory.apply(properties.setId(id));
        return Registry.register(BuiltInRegistries.BLOCK, id, block);
    }

    private static Block register(BlockItemId id, Function<BlockBehaviour.Properties, Block> blockFactory, BlockBehaviour.Properties properties) {
        Block block = register(id.block(), blockFactory, properties);
        BlockItem blockItem = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(id.item()));
        Registry.register(BuiltInRegistries.ITEM, id.item(), blockItem);
        return block;
    }

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(tab -> {
            tab.accept(CIRCUIT_BLOCK.asItem());
            tab.accept(SIGNAL_WIRE.asItem());
            tab.accept(BUS_CABLE_2.asItem());
            tab.accept(BUS_CABLE_4.asItem());
            tab.accept(BUS_CABLE.asItem());
            tab.accept(BUS_CABLE_16.asItem());
            tab.accept(BUS_CABLE_32.asItem());
        });
    }
}
