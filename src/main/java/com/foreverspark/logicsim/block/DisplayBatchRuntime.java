package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Server-thread fast path for high-rate simulated display output.
 *
 * The circuit simulator may produce hundreds of thousands of DATA64 commands per second. Sending every command
 * through CableRuntime and rebuilding the display wall for every pixel makes the visible screen run at Minecraft
 * I/O speed instead of simulation speed. This helper resolves the wall once, then applies a whole coalesced
 * framebuffer batch directly to the physical display backing stores. Client synchronization remains naturally
 * coalesced by DisplayBlockEntity's normal tick.
 */
public final class DisplayBatchRuntime {
    private static final int MAX_WALL_BLOCKS = 4096;

    private DisplayBatchRuntime() {}

    public static Result apply(Level level, BlockPos touchedTile, long[] commands) {
        if (level == null || level.isClientSide() || touchedTile == null || commands == null || commands.length == 0) {
            return new Result(0, 0, 0);
        }

        BlockState touchedState = level.getBlockState(touchedTile);
        DisplayWall wall = collectWall(level, touchedTile, touchedState);
        if (wall == null || wall.blocks().isEmpty()) return new Result(0, 0, 0);

        BlockPos controllerPos = wall.blocks().stream()
                .min(Comparator.comparingLong(BlockPos::asLong))
                .orElse(touchedTile);
        if (!(level.getBlockEntity(controllerPos) instanceof DisplayBlockEntity controller)) {
            return new Result(0, 0, commands.length);
        }
        if (!controller.wallPowered()) return new Result(0, 0, commands.length);

        int density = controller.pixelWidth();
        int columns = wall.maxHorizontal() - wall.minHorizontal() + 1;
        int rows = wall.maxY() - wall.minY() + 1;
        int screenWidth = columns * density;
        int screenHeight = rows * density;

        int applied = 0;
        int cleared = 0;
        int rejected = 0;

        for (long raw : commands) {
            DisplayCommandCodec.Command command = DisplayCommandCodec.decode(raw);
            if (command.isClear()) {
                for (BlockPos pos : wall.blocks()) {
                    if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) display.clearScreen();
                }
                cleared++;
                continue;
            }
            if (!command.isPixel()) {
                rejected++;
                continue;
            }

            int globalX = command.x();
            int globalY = command.y();
            if (globalX < 0 || globalY < 0 || globalX >= screenWidth || globalY >= screenHeight) {
                rejected++;
                continue;
            }

            BlockPos target = targetForGlobalPixel(wall, touchedTile, density, globalX, globalY);
            if (target == null || !wall.blocks().contains(target)) {
                rejected++;
                continue;
            }
            if (!(level.getBlockEntity(target) instanceof DisplayBlockEntity display)) {
                rejected++;
                continue;
            }

            display.writePixel(globalX % density, globalY % density, command.rgb565());
            applied++;
        }

        return new Result(applied, cleared, rejected);
    }

    private static BlockPos targetForGlobalPixel(DisplayWall wall, BlockPos origin, int density, int globalX, int globalY) {
        int targetHorizontal = wall.minHorizontal() + globalX / density;
        int targetY = wall.maxY() - globalY / density;
        return origin.offset(
                wall.right().getStepX() * targetHorizontal,
                targetY - origin.getY(),
                wall.right().getStepZ() * targetHorizontal
        );
    }

    private static DisplayWall collectWall(Level level, BlockPos start, BlockState startState) {
        if (level == null || start == null || !(startState.getBlock() instanceof DisplayBlock)) return null;
        Direction facing = DisplayPorts.front(startState);
        Direction left = DisplayPorts.left(startState);
        Direction right = left.getOpposite();

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> blocks = new HashSet<>();
        queue.add(start.immutable());

        int minHorizontal = 0;
        int maxHorizontal = 0;
        int minY = start.getY();
        int maxY = start.getY();

        while (!queue.isEmpty() && seen.size() < MAX_WALL_BLOCKS) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DisplayBlock) || DisplayPorts.front(state) != facing) continue;

            blocks.add(pos.immutable());
            int dx = pos.getX() - start.getX();
            int dz = pos.getZ() - start.getZ();
            int horizontal = dx * right.getStepX() + dz * right.getStepZ();
            minHorizontal = Math.min(minHorizontal, horizontal);
            maxHorizontal = Math.max(maxHorizontal, horizontal);
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());

            queue.add(pos.relative(left));
            queue.add(pos.relative(right));
            queue.add(pos.relative(Direction.UP));
            queue.add(pos.relative(Direction.DOWN));
        }

        return new DisplayWall(Set.copyOf(blocks), right, minHorizontal, maxHorizontal, minY, maxY);
    }

    public record Result(int appliedPixels, int clears, int rejectedCommands) {}
    private record DisplayWall(Set<BlockPos> blocks, Direction right, int minHorizontal, int maxHorizontal, int minY, int maxY) {}
}
