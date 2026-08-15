package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Latest discovered physical peripherals for one world Circuit Block. */
public record ExternalDevicesPayload(BlockPos pos, String devicesJson) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = CircuitBoardPayload.MAX_DEVICES_JSON;
    public static final Type<ExternalDevicesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
            LogicSimulationMod.MOD_ID, "external_devices"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ExternalDevicesPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ExternalDevicesPayload::pos,
            ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ExternalDevicesPayload::devicesJson,
            ExternalDevicesPayload::new
    );

    public ExternalDevicesPayload {
        if (pos == null) throw new IllegalArgumentException("External device snapshot requires a block position");
        devicesJson = devicesJson == null ? "[]" : devicesJson;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
