package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RequestCircuitPortsPayload(BlockPos socketPos) implements CustomPacketPayload {
    public static final Type<RequestCircuitPortsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "request_circuit_ports")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCircuitPortsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestCircuitPortsPayload::socketPos,
            RequestCircuitPortsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
