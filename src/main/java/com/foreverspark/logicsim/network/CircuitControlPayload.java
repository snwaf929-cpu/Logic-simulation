package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CircuitControlPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<CircuitControlPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "circuit_control")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CircuitControlPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CircuitControlPayload::pos,
            CircuitControlPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
