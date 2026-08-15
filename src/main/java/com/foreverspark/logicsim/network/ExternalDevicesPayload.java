package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Latest discovered physical peripherals for one world Circuit Block. */
public record ExternalDevicesPayload(BlockPos pos, String devicesJson) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = CircuitBoardPayload.MAX_DEVICES_JSON;
    public static final Type<ExternalDevicesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(
            LogicSimulationMod.MOD_ID, "external_devices"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExternalDevicesPayload> CODEC = new StreamCodec<>() {
        @Override
        public ExternalDevicesPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
            return new ExternalDevicesPayload(pos, buffer.readUtf(MAX_JSON_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ExternalDevicesPayload payload) {
            String json = payload.devicesJson() == null ? "[]" : payload.devicesJson();
            if (json.length() > MAX_JSON_LENGTH) throw new EncoderException("External device snapshot is too large");
            BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
            buffer.writeUtf(json, MAX_JSON_LENGTH);
        }
    };

    public ExternalDevicesPayload {
        if (pos == null) throw new DecoderException("External device snapshot requires a block position");
        devicesJson = devicesJson == null ? "[]" : devicesJson;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
