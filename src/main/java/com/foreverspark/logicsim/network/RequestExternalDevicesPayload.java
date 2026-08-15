package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Device-only refresh request. Unlike RequestCircuitBoardPayload this never reopens the editor. */
public record RequestExternalDevicesPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestExternalDevicesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
            LogicSimulationMod.MOD_ID, "request_external_devices"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestExternalDevicesPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestExternalDevicesPayload::pos,
            RequestExternalDevicesPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
