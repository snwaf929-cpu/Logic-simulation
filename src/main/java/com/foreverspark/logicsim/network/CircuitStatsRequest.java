package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CircuitStatsRequest(BlockPos circuitPos) implements CustomPacketPayload {
    public static final Type<CircuitStatsRequest> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "circuit_stats")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CircuitStatsRequest> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CircuitStatsRequest::circuitPos,
            CircuitStatsRequest::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
