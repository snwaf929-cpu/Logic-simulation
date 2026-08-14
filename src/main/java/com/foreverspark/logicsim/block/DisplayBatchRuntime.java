package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
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
 * The simulation worker already coalesces repeated writes to the same global pixel. This class now resolves the
 * physical wall exactly once per Minecraft flush, builds a flat tile lookup, decodes raw DATA64 bits without
 * allocating Command records, writes framebuffer memory directly, and marks each dirty tile changed only once.
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

        // Resolve every physical tile once. The hot pixel loop below contains no world/hash/block-entity lookup.
        DisplayBlockEntity[] tiles = new DisplayBlockEntity[columns * rows];
        boolean[] dirtyTiles = new boolean[tiles.length];
        for (BlockPos pos : wall.blocks()) {
            int dx = pos.getX() - touchedTile.getX();
            int dz = pos.getZ() - touchedTile.getZ();
            int horizontal = dx * wall.right().getStepX() + dz * wall.right().getStepZ();
            int tileX = horizontal - wall.minHorizontal();
            int tileY = wall.maxY() - pos.getY();
            if (tileX < 0 || tileX >= columns || tileY < 0 || tileY >= rows) continue;
            if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
                tiles[tileY * columns + tileX] = display;
            }
        }

        int applied = 0;
        int cleared = 0;
        int rejected = 0;
        int backingScale = DisplayBlockEntity.MAX_WIDTH / Math.max(1, density);

        for (long raw : commands) {
            int opcode = (int) ((raw >>> 48) & 0xFFL);
            if (opcode == DisplayCommandCodec.OP_CLEAR) {
                for (int tileIndex = 0; tileIndex < tiles.length; tileIndex++) {
                    DisplayBlockEntity display = tiles[tileIndex];
                    if (display == null) continue;
                    DisplayFramebuffer framebuffer = display.framebuffer();
                    long before = framebuffer.revision();
                    framebuffer.clear(0);
                    if (framebuffer.revision() != before) dirtyTiles[tileIndex] = true;
                }
                cleared++;
                continue;
            }
            if (opcode != DisplayCommandCodec.OP_PIXEL) {
                rejected++;
                continue;
            }

            int globalX = (int) ((raw >>> 16) & 0xFFFFL);
            int globalY = (int) ((raw >>> 32) & 0xFFFFL);
            if (globalX < 0 || globalY < 0 || globalX >= screenWidth || globalY >= screenHeight) {
                rejected++;
                continue;
            }

            int tileX = globalX / density;
            int tileY = globalY / density;
            int tileIndex = tileY * columns + tileX;
            DisplayBlockEntity display = tiles[tileIndex];
            if (display == null) {
                rejected++;
                continue;
            }

            int localX = globalX - tileX * density;
            int localY = globalY - tileY * density;
            int rgb565 = (int) (raw & 0xFFFFL);
            DisplayFramebuffer framebuffer = display.framebuffer();
            long before = framebuffer.revision();

            if (backingScale == 1) {
                framebuffer.writePixel(localX, localY, rgb565);
            } else {
                int minX = localX * backingScale;
                int minY = localY * backingScale;
                framebuffer.fillRect(
                        minX,
                        minY,
                        minX + backingScale - 1,
                        minY + backingScale - 1,
                        rgb565
                );
            }

            if (framebuffer.revision() != before) dirtyTiles[tileIndex] = true;
            applied++;
        }

        // One chunk-dirty/client-sync request per changed tile, never one per simulated pixel.
        for (int tileIndex = 0; tileIndex < tiles.length; tileIndex++) {
            if (!dirtyTiles[tileIndex]) continue;
            DisplayBlockEntity display = tiles[tileIndex];
            if (display != null) display.setChanged();
        }

        return new Result(applied, cleared, rejected);
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
