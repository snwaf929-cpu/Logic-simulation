package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BindCircuitPortPayload(BlockPos socketPos, BlockPos circuitPos, String portName, String direction) implements CustomPacketPayload {
    public static final Type<BindCircuitPortPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "bind_circuit_port"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BindCircuitPortPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BindCircuitPortPayload::socketPos,
            BlockPos.STREAM_CODEC, BindCircuitPortPayload::circuitPos,
            ByteBufCodecs.stringUtf8(64), BindCircuitPortPayload::portName,
            ByteBufCodecs.stringUtf8(12), BindCircuitPortPayload::direction,
            BindCircuitPortPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
