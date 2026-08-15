package com.foreverspark.logicsim.network;

import com.foreverspark.logicsim.LogicSimulationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Manual editor control for one root INPUT on an already-running physical Circuit Block. */
public record DriveCircuitInputPayload(BlockPos pos, String portName, String valueHex) implements CustomPacketPayload {
    public static final Type<DriveCircuitInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "drive_circuit_input")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DriveCircuitInputPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DriveCircuitInputPayload::pos,
            ByteBufCodecs.stringUtf8(64), DriveCircuitInputPayload::portName,
            ByteBufCodecs.stringUtf8(16), DriveCircuitInputPayload::valueHex,
            DriveCircuitInputPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
