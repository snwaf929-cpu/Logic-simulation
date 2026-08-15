package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CircuitBoardPayload(BlockPos pos, String boardJson, String devicesJson) implements CustomPacketPayload {
    public static final int MAX_DEVICES_JSON = 256_000;
    public static final Type<CircuitBoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "circuit_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CircuitBoardPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CircuitBoardPayload::pos,
            ByteBufCodecs.stringUtf8(CircuitBlockEntity.MAX_BOARD_JSON), CircuitBoardPayload::boardJson,
            ByteBufCodecs.stringUtf8(MAX_DEVICES_JSON), CircuitBoardPayload::devicesJson,
            CircuitBoardPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
