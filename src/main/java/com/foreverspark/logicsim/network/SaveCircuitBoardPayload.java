package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SaveCircuitBoardPayload(BlockPos pos, String boardJson) implements CustomPacketPayload {
    public static final Type<SaveCircuitBoardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "save_circuit_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SaveCircuitBoardPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveCircuitBoardPayload::pos,
            ByteBufCodecs.stringUtf8(CircuitBlockEntity.MAX_BOARD_JSON), SaveCircuitBoardPayload::boardJson,
            SaveCircuitBoardPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
