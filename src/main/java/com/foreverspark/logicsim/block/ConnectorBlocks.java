package com.foreverspark.logicsim.block;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ConnectorBlocks {
    public static final IoConnectorBlock IO_CONNECTOR;

    static {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().sound(SoundType.METAL).noOcclusion();
        IO_CONNECTOR = new IoConnectorBlock(properties.setId(ModBlockItemIds.IO_CONNECTOR.block()));
        Registry.register(BuiltInRegistries.BLOCK, ModBlockItemIds.IO_CONNECTOR.block(), IO_CONNECTOR);
        BlockItem item = new BlockItem(IO_CONNECTOR, new Item.Properties().useBlockDescriptionPrefix().setId(ModBlockItemIds.IO_CONNECTOR.item()));
        Registry.register(BuiltInRegistries.ITEM, ModBlockItemIds.IO_CONNECTOR.item(), item);
    }

    private ConnectorBlocks() {}

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(tab -> tab.accept(IO_CONNECTOR.asItem()));
    }
}
