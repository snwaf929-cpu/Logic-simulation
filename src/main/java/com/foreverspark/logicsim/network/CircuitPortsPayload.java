package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CircuitPortsPayload(BlockPos socketPos, BlockPos circuitPos, String catalogJson) implements CustomPacketPayload {
    public static final int MAX_CATALOG_JSON = 131072;
    public static final Type<CircuitPortsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "circuit_ports"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CircuitPortsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CircuitPortsPayload::socketPos,
            BlockPos.STREAM_CODEC, CircuitPortsPayload::circuitPos,
            ByteBufCodecs.stringUtf8(MAX_CATALOG_JSON), CircuitPortsPayload::catalogJson,
            CircuitPortsPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
