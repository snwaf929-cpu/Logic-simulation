package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RequestCircuitBoardPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestCircuitBoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "request_circuit_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCircuitBoardPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestCircuitBoardPayload::pos,
            RequestCircuitBoardPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
