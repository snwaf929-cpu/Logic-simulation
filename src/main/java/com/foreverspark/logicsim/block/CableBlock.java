package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.interconnect.CableKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class CableBlock extends Block {
    private static final Property<Boolean> NORTH = BlockStateProperties.NORTH;
    private static final Property<Boolean> SOUTH = BlockStateProperties.SOUTH;
    private static final Property<Boolean> WEST = BlockStateProperties.WEST;
    private static final Property<Boolean> EAST = BlockStateProperties.EAST;
    private static final Property<Boolean> UP = BlockStateProperties.UP;
    private static final Property<Boolean> DOWN = BlockStateProperties.DOWN;

    private final CableKind cableKind;
    private final int bitWidth;

    public CableBlock(CableKind cableKind, int bitWidth, BlockBehaviour.Properties properties) {
        super(properties);
        cableKind.validateWidth(bitWidth);
        this.cableKind = cableKind;
        this.bitWidth = bitWidth;
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(EAST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    public CableKind cableKind() { return cableKind; }
    public int bitWidth() { return bitWidth; }

    public boolean compatibleWith(CableBlock other) {
        return other != null && cableKind == other.cableKind && bitWidth == other.bitWidth;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        BlockPos pos = context.getClickedPos();
        for (Direction direction : Direction.values()) {
            state = state.setValue(property(direction), connectsTo(context.getLevel(), pos, direction));
        }
        return state;
    }

    protected BlockState updateShapeLegacy(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(property(direction), connectsTo(level, pos, direction, neighborState));
    }

    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, net.minecraft.util.RandomSource random) {
        return state.setValue(property(direction), connectsTo(level, pos, direction, neighborState));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, WEST, EAST, UP, DOWN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CableGeometry.shape(state, cableKind == CableKind.BUS);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    public boolean connectsVisually(BlockGetter level, BlockPos pos, Direction direction, BlockState neighborState) {
        return connectsTo(level, pos, direction, neighborState);
    }

    public static Property<Boolean> connectionProperty(Direction direction) {
        return property(direction);
    }

    private boolean connectsTo(BlockGetter level, BlockPos pos, Direction direction) {
        BlockPos neighborPos = pos.relative(direction);
        return connectsTo(level, pos, direction, level.getBlockState(neighborPos));
    }

    private boolean connectsTo(BlockGetter level, BlockPos pos, Direction direction, BlockState neighborState) {
        BlockPos neighborPos = pos.relative(direction);
        if (neighborState.getBlock() instanceof CableBlock other) return compatibleWith(other);
        if (level.getBlockEntity(neighborPos) instanceof CircuitPortBlockEntity socket) return socket.accepts(this);
        if (neighborState.getBlock() instanceof DisplayBlock) {
            return DisplayPorts.widthAt(neighborState, direction.getOpposite()) == bitWidth;
        }
        return false;
    }

    private static Property<Boolean> property(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }
}
