package com.foreverspark.logicsim.block;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DisplayBlocks {
    public static final DisplayBlock DISPLAY_BLOCK;

    static {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion();
        DISPLAY_BLOCK = new DisplayBlock(properties.setId(ModBlockItemIds.DISPLAY_BLOCK.block()));
        Registry.register(BuiltInRegistries.BLOCK, ModBlockItemIds.DISPLAY_BLOCK.block(), DISPLAY_BLOCK);
        BlockItem item = new BlockItem(DISPLAY_BLOCK, new Item.Properties().useBlockDescriptionPrefix().setId(ModBlockItemIds.DISPLAY_BLOCK.item()));
        Registry.register(BuiltInRegistries.ITEM, ModBlockItemIds.DISPLAY_BLOCK.item(), item);
    }

    private DisplayBlocks() {}

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(tab -> tab.accept(DISPLAY_BLOCK.asItem()));
    }
}
