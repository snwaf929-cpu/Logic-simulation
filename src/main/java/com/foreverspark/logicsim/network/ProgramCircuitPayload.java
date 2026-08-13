package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ProgramCircuitPayload(BlockPos pos, String programJson) implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = 2_000_000;
    public static final String CONTROL_TOGGLE_REDSTONE_CLOCK_GATE = "__LOGICSIM_CONTROL_REDSTONE_CLOCK_GATE__";
    public static final Type<ProgramCircuitPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "program_circuit")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ProgramCircuitPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ProgramCircuitPayload::pos,
            ByteBufCodecs.stringUtf8(MAX_JSON_LENGTH), ProgramCircuitPayload::programJson,
            ProgramCircuitPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
