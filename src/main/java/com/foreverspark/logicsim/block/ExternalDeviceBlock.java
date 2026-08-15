package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.editor.model.ExternalDeviceType;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Physical endpoint for host-facing peripherals. It contains no simulated logic. */
public final class ExternalDeviceBlock extends BaseEntityBlock {
    private final ExternalDeviceType deviceType;

    public ExternalDeviceBlock(ExternalDeviceType deviceType, Properties properties) {
        super(properties);
        this.deviceType = deviceType;
    }

    public ExternalDeviceType deviceType() { return deviceType; }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExternalDeviceBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.EXTERNAL_DEVICE, ExternalDeviceBlockEntity::tick);
    }

    /** Fixed face contract keeps discovery deterministic and makes cable width/type validation cheap. */
    public PortSpec portOnFace(Direction face) {
        return switch (deviceType) {
            case UIB -> switch (face) {
                case NORTH -> input(0);
                case SOUTH -> output(0);
                case EAST -> output(1);
                case UP -> output(2);
                default -> null;
            };
            case INTERNET -> switch (face) {
                case NORTH -> input(0);
                case EAST -> input(1);
                case SOUTH -> output(0);
                case WEST -> output(1);
                case UP -> output(2);
                default -> null;
            };
            case STORAGE -> switch (face) {
                case NORTH -> input(0);
                case SOUTH -> output(0);
                case UP -> output(1);
                default -> null;
            };
            case DISPLAY -> null;
        };
    }

    public boolean accepts(Direction face, CableBlock cable) {
        PortSpec port = portOnFace(face);
        if (port == null || cable == null) return false;
        CableKind expected = port.width() == 1 ? CableKind.SIGNAL : CableKind.BUS;
        return cable.cableKind() == expected && cable.bitWidth() == port.width();
    }

    private PortSpec input(int index) { return deviceType.inputs().get(index); }
    private PortSpec output(int index) { return deviceType.outputs().get(index); }
}
