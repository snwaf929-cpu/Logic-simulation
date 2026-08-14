package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.block.CableBlock;
import com.foreverspark.logicsim.block.CircuitBlockEntity;
import com.foreverspark.logicsim.block.CircuitPortBlockEntity;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.editor.model.PortDirection;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.CableNetworkCache;
import com.foreverspark.logicsim.interconnect.CircuitPortLinks;
import com.foreverspark.logicsim.interconnect.DirectPortResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integrated-client fast path for physical Pixel Display walls.
 *
 * The dedicated logic worker and the render thread live in the same JVM in single-player. Sending millions of
 * simulated DATA64 writes through the integrated server's block-entity tick/network/save path only to arrive back in
 * that same JVM is pure overhead and makes the display inherit Minecraft's 20 TPS stalls. This registry keeps the
 * authoritative transient display framebuffer in primitive memory instead. The simulation worker writes it directly;
 * the client renderer samples it at its own frame cadence.
 */
public final class RealtimeDisplaySurface {
    private static final int MAX_WALL_BLOCKS = 4096;
    private static final int BACKING_TILE_SIZE = DisplayBlockEntity.MAX_WIDTH;
    private static final int BACKING_TILE_SHIFT = 6; // MAX_WIDTH is 64.

    private static final Map<RouteKey, Surface> ROUTES = new ConcurrentHashMap<>();
    private static final Map<Long, TileView> TILES = new ConcurrentHashMap<>();
    private static final Map<Long, Surface> CONTROLLERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> BYPASS_BUFFER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RealtimeDisplaySurface() {}

    public static void refreshRoutes(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        Level level = circuit.getLevel();
        if (level == null || level.isClientSide()) return;

        long circuitKey = circuit.getBlockPos().asLong();
        ROUTES.keySet().removeIf(key -> key.circuitPos == circuitKey);

        Map<String, Surface> resolved = new HashMap<>();
        for (BlockPos socketPos : CircuitPortLinks.sockets(level, circuit.getBlockPos())) {
            if (!(level.getBlockEntity(socketPos) instanceof CircuitPortBlockEntity socket)) continue;
            if (socket.direction() != PortDirection.OUTPUT || socket.width() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            for (Direction direction : Direction.values()) {
                BlockPos cablePos = socketPos.relative(direction);
                BlockState state = level.getBlockState(cablePos);
                if (!(state.getBlock() instanceof CableBlock cable)) continue;
                if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;
                if (!socket.accepts(cable)) continue;
                Surface surface = surfaceForCable(level, cablePos);
                if (surface != null) resolved.putIfAbsent(socket.portName(), surface);
            }
        }

        for (Direction direction : Direction.values()) {
            BlockPos cablePos = circuit.getBlockPos().relative(direction);
            BlockState state = level.getBlockState(cablePos);
            if (!(state.getBlock() instanceof CableBlock cable)) continue;
            if (cable.cableKind() != CableKind.BUS || cable.bitWidth() != DisplayBlockEntity.DISPLAY_BUS_WIDTH) continue;

            PortSpec port = DirectPortResolver.unique(circuit, cable.cableKind(), cable.bitWidth());
            if (port == null || port.direction() != PortDirection.OUTPUT) continue;
            Surface surface = surfaceForCable(level, cablePos);
            if (surface != null) resolved.putIfAbsent(port.name(), surface);
        }

        for (Map.Entry<String, Surface> entry : resolved.entrySet()) {
            ROUTES.put(new RouteKey(circuitKey, entry.getKey()), entry.getValue());
        }
    }

    public static void removeRoutes(CircuitBlockEntity circuit) {
        if (circuit == null) return;
        long circuitKey = circuit.getBlockPos().asLong();
        ROUTES.keySet().removeIf(key -> key.circuitPos == circuitKey);
    }

    public static Surface route(BlockPos circuitPos, String portName) {
        if (circuitPos == null || portName == null) return null;
        return ROUTES.get(new RouteKey(circuitPos.asLong(), portName));
    }

    public static TileView tileView(BlockPos tilePos) {
        return tilePos == null ? null : TILES.get(tilePos.asLong());
    }

    public static void beginCapture(boolean realtimeDisplayMapped) {
        BYPASS_BUFFER.set(realtimeDisplayMapped);
    }

    public static void endCapture() {
        BYPASS_BUFFER.remove();
    }

    public static boolean bypassServerDisplayBuffer() {
        return BYPASS_BUFFER.get();
    }

    private static Surface surfaceForCable(Level level, BlockPos cablePos) {
        CableNetworkCache.Network network = CableNetworkCache.network(level, cablePos);
        if (network == null) return null;
        for (CableNetworkCache.Endpoint endpoint : network.endpoints()) {
            if (endpoint.kind() != CableNetworkCache.EndpointKind.DISPLAY) continue;
            BlockState displayState = level.getBlockState(endpoint.devicePos());
            if (!(displayState.getBlock() instanceof DisplayBlock)) continue;
            DisplayBlockEntity.WallInfo info = DisplayBlockEntity.wallInfo(level, endpoint.devicePos(), displayState);
            if (info == null || info.pixelWidth() <= 0 || info.pixelHeight() <= 0) continue;
            return configureWall(level, endpoint.devicePos(), displayState, info.pixelsPerTile());
        }
        return null;
    }

    private static synchronized Surface configureWall(Level level, BlockPos touchedTile, BlockState touchedState, int density) {
        WallGeometry wall = collectWall(level, touchedTile, touchedState);
        if (wall == null || wall.blocks.isEmpty()) return null;

        BlockPos controllerPos = wall.blocks.stream()
                .min((a, b) -> Long.compare(a.asLong(), b.asLong()))
                .orElse(touchedTile);
        long controllerKey = controllerPos.asLong();
        int columns = wall.maxHorizontal - wall.minHorizontal + 1;
        int rows = wall.maxY - wall.minY + 1;
        int normalizedDensity = Math.max(1, Math.min(BACKING_TILE_SIZE, density));

        Surface existing = CONTROLLERS.get(controllerKey);
        if (existing != null
                && existing.columns == columns
                && existing.rows == rows
                && existing.density == normalizedDensity
                && existing.tileCount == wall.blocks.size()) {
            return existing;
        }

        if (existing != null) {
            for (long tileKey : existing.tileKeys) {
                TileView view = TILES.get(tileKey);
                if (view != null && view.surface == existing) TILES.remove(tileKey, view);
            }
        }

        Surface surface = new Surface(controllerPos, columns, rows, normalizedDensity, wall.blocks.size());
        long[] tileKeys = new long[columns * rows];
        Arrays.fill(tileKeys, Long.MIN_VALUE);

        for (BlockPos pos : wall.blocks) {
            int dx = pos.getX() - touchedTile.getX();
            int dz = pos.getZ() - touchedTile.getZ();
            int horizontal = dx * wall.right.getStepX() + dz * wall.right.getStepZ();
            int tileX = horizontal - wall.minHorizontal;
            int tileY = wall.maxY - pos.getY();
            if (tileX < 0 || tileX >= columns || tileY < 0 || tileY >= rows) continue;
            int tileIndex = tileY * columns + tileX;
            long tileKey = pos.asLong();
            tileKeys[tileIndex] = tileKey;
            TILES.put(tileKey, new TileView(surface, tileX, tileY));
        }

        surface.tileKeys = tileKeys;
        CONTROLLERS.put(controllerKey, surface);
        return surface;
    }

    private static WallGeometry collectWall(Level level, BlockPos start, BlockState startState) {
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

        return new WallGeometry(right, Set.copyOf(blocks), minHorizontal, maxHorizontal, minY, maxY);
    }

    private record RouteKey(long circuitPos, String portName) {}
    private record WallGeometry(Direction right, Set<BlockPos> blocks, int minHorizontal, int maxHorizontal, int minY, int maxY) {}

    public static final class TileView {
        private final Surface surface;
        private final int tileX;
        private final int tileY;

        private TileView(Surface surface, int tileX, int tileY) {
            this.surface = surface;
            this.tileX = tileX;
            this.tileY = tileY;
        }

        public Surface surface() { return surface; }
        public int tileX() { return tileX; }
        public int tileY() { return tileY; }
    }

    /** Single-writer framebuffer. publishedRevision is the release/acquire publication barrier for the render thread. */
    public static final class Surface {
        private final BlockPos controllerPos;
        private final int columns;
        private final int rows;
        private final int density;
        private final int logicalWidth;
        private final int logicalHeight;
        private final int backingWidth;
        private final int backingHeight;
        private final int tileCount;
        /** RGB565 is exactly 16 bits. char[] halves the 2048x2048 transient surface from 16 MiB to 8 MiB. */
        private final char[] pixels;
        private final long[] tileRevisions;
        /** Reused 64-bit dirty words; a 32x32 tile wall needs only 16 longs. */
        private final long[] batchDirtyTileWords;
        private long[] tileKeys;
        private long revision;
        private volatile long publishedRevision;
        /** Published by the same volatile revision barrier; intentionally not volatile in the MHz writer loop. */
        private int nonZeroPixels;

        private Surface(BlockPos controllerPos, int columns, int rows, int density, int tileCount) {
            this.controllerPos = controllerPos.immutable();
            this.columns = columns;
            this.rows = rows;
            this.density = density;
            this.logicalWidth = columns * density;
            this.logicalHeight = rows * density;
            this.backingWidth = columns * BACKING_TILE_SIZE;
            this.backingHeight = rows * BACKING_TILE_SIZE;
            this.tileCount = tileCount;
            this.pixels = new char[Math.multiplyExact(backingWidth, backingHeight)];
            this.tileRevisions = new long[columns * rows];
            this.batchDirtyTileWords = new long[(tileRevisions.length + 63) >>> 6];
            this.tileKeys = new long[columns * rows];
        }

        public void record(long raw) {
            long before = revision;
            recordUnpublished(raw);
            if (revision != before) publishedRevision = revision;
        }

        /** Apply a complete simulator batch with one release publication and one tile-revision commit. */
        public void recordBatch(long[] raws, int count) {
            if (raws == null || count <= 0) return;
            int limit = Math.min(count, raws.length);

            if (density == BACKING_TILE_SIZE) {
                if (recordNative64Batch(raws, limit)) publishedRevision = revision;
                return;
            }

            long before = revision;
            for (int index = 0; index < limit; index++) recordUnpublished(raws[index]);
            if (revision != before) publishedRevision = revision;
        }

        /**
         * 64 pixels/block makes logical coordinates identical to backing coordinates. Pixel writes update only the
         * 8 MiB RGB565 array plus a tiny 1024-bit tile-dirty mask. Revision/tile metadata is committed once per 64K
         * edge chunk instead of once per virtual pixel write.
         */
        private boolean recordNative64Batch(long[] raws, int limit) {
            Arrays.fill(batchDirtyTileWords, 0L);
            boolean changed = false;
            boolean allTilesDirty = false;

            for (int commandIndex = 0; commandIndex < limit; commandIndex++) {
                long raw = raws[commandIndex];
                int opcode = (int) ((raw >>> 48) & 0xFFL);
                if (opcode == DisplayCommandCodec.OP_PIXEL) {
                    int globalX = (int) ((raw >>> 16) & 0xFFFFL);
                    int globalY = (int) ((raw >>> 32) & 0xFFFFL);
                    if (globalX >= logicalWidth || globalY >= logicalHeight) continue;

                    int rgb565 = (int) raw & 0xFFFF;
                    int pixelIndex = globalY * backingWidth + globalX;
                    int previous = pixels[pixelIndex];
                    if (previous == rgb565) continue;
                    pixels[pixelIndex] = (char) rgb565;
                    if (previous == 0 && rgb565 != 0) nonZeroPixels++;
                    else if (previous != 0 && rgb565 == 0) nonZeroPixels--;
                    changed = true;

                    if (!allTilesDirty) {
                        int tileIndex = (globalY >>> BACKING_TILE_SHIFT) * columns + (globalX >>> BACKING_TILE_SHIFT);
                        batchDirtyTileWords[tileIndex >>> 6] |= 1L << (tileIndex & 63);
                    }
                    continue;
                }

                if (opcode == DisplayCommandCodec.OP_CLEAR && nonZeroPixels != 0) {
                    Arrays.fill(pixels, (char) 0);
                    nonZeroPixels = 0;
                    changed = true;
                    allTilesDirty = true;
                    Arrays.fill(batchDirtyTileWords, 0L);
                }
            }

            if (!changed) return false;
            long next = ++revision;
            if (allTilesDirty) {
                Arrays.fill(tileRevisions, next);
            } else {
                commitDirtyTiles(next);
            }
            return true;
        }

        private void commitDirtyTiles(long revisionValue) {
            for (int wordIndex = 0; wordIndex < batchDirtyTileWords.length; wordIndex++) {
                long word = batchDirtyTileWords[wordIndex];
                while (word != 0L) {
                    int bit = Long.numberOfTrailingZeros(word);
                    int tileIndex = (wordIndex << 6) + bit;
                    if (tileIndex < tileRevisions.length) tileRevisions[tileIndex] = revisionValue;
                    word &= word - 1L;
                }
            }
        }

        private void recordUnpublished(long raw) {
            int opcode = (int) ((raw >>> 48) & 0xFFL);
            if (opcode == DisplayCommandCodec.OP_CLEAR) {
                clearUnpublished();
                return;
            }
            if (opcode != DisplayCommandCodec.OP_PIXEL) return;

            int globalX = (int) ((raw >>> 16) & 0xFFFFL);
            int globalY = (int) ((raw >>> 32) & 0xFFFFL);
            if (globalX >= logicalWidth || globalY >= logicalHeight) return;

            int rgb565 = (int) raw & 0xFFFF;

            if (density == BACKING_TILE_SIZE) {
                int pixelIndex = globalY * backingWidth + globalX;
                int previous = pixels[pixelIndex];
                if (previous == rgb565) return;
                pixels[pixelIndex] = (char) rgb565;
                if (previous == 0 && rgb565 != 0) nonZeroPixels++;
                else if (previous != 0 && rgb565 == 0) nonZeroPixels--;
                long next = ++revision;
                int tileIndex = (globalY >>> BACKING_TILE_SHIFT) * columns + (globalX >>> BACKING_TILE_SHIFT);
                tileRevisions[tileIndex] = next;
                return;
            }

            int tileX = globalX / density;
            int tileY = globalY / density;
            int localX = globalX - tileX * density;
            int localY = globalY - tileY * density;
            int scale = BACKING_TILE_SIZE / density;
            int backingX = tileX * BACKING_TILE_SIZE + localX * scale;
            int backingY = tileY * BACKING_TILE_SIZE + localY * scale;
            int tileIndex = tileY * columns + tileX;

            int changed = 0;
            int nonZeroDelta = 0;
            for (int y = 0; y < scale; y++) {
                int row = (backingY + y) * backingWidth + backingX;
                for (int x = 0; x < scale; x++) {
                    int index = row + x;
                    int previous = pixels[index];
                    if (previous == rgb565) continue;
                    pixels[index] = (char) rgb565;
                    if (previous == 0 && rgb565 != 0) nonZeroDelta++;
                    else if (previous != 0 && rgb565 == 0) nonZeroDelta--;
                    changed++;
                }
            }
            if (changed == 0) return;

            if (nonZeroDelta != 0) nonZeroPixels += nonZeroDelta;
            long next = ++revision;
            tileRevisions[tileIndex] = next;
        }

        private void clearUnpublished() {
            if (nonZeroPixels == 0) return;
            Arrays.fill(pixels, (char) 0);
            nonZeroPixels = 0;
            long next = ++revision;
            Arrays.fill(tileRevisions, next);
        }

        public BlockPos controllerPos() { return controllerPos; }
        public int columns() { return columns; }
        public int rows() { return rows; }
        public int density() { return density; }
        public int logicalWidth() { return logicalWidth; }
        public int logicalHeight() { return logicalHeight; }
        public int backingWidth() { return backingWidth; }
        public int backingHeight() { return backingHeight; }
        public int tileSlots() { return tileRevisions.length; }
        public long publishedRevision() { return publishedRevision; }
        public int nonZeroPixels() { return nonZeroPixels; }
        public long tileRevision(int tileIndex) { return tileRevisions[tileIndex]; }
        public int pixelRgb565(int x, int y) { return pixels[y * backingWidth + x]; }
    }
}