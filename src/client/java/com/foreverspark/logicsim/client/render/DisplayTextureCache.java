package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.DisplayBlock;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.block.DisplayPorts;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU texture cache for physical Pixel Display walls.
 *
 * A connected wall now shares ONE DynamicTexture. Every physical display block samples only its own UV slice from
 * that wall texture, so a 32x32-block screen no longer creates/binds 1024 different textures. Minecraft can batch the
 * visible tile quads under one render type/texture while normal per-block frustum culling remains intact.
 *
 * The cache also converts RGB565 through a precomputed 65,536-entry lookup table instead of repeating bit expansion
 * millions of times while rebuilding large 1080p/2K framebuffers.
 */
public final class DisplayTextureCache {
    private static final long STALE_NANOS = 10_000_000_000L;
    private static final long PRUNE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long TOPOLOGY_RECHECK_NANOS = 1_000_000_000L;
    /** Prevent every tile extraction in one frame from rescanning all wall revisions. */
    private static final long WALL_REVISION_SCAN_MIN_NANOS = 2_000_000L;
    private static final int MAX_WALL_BLOCKS = 4096;
    /** Conservative shared-texture cap. Larger/extreme line-shaped walls fall back to one texture per tile. */
    private static final int MAX_SHARED_TEXTURE_DIMENSION = 8192;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int[] RGB565_TO_ARGB = buildRgb565Lookup();

    /** Position -> precompiled wall/UV binding. All tiles in one wall point to the same WallEntry. */
    private static final Map<Long, TileBinding> TILE_BINDINGS = new HashMap<>();
    private static final Set<WallEntry> WALLS = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Safety fallback for a wall too large for one practical GPU texture. */
    private static final Map<DisplayBlockEntity, TileEntry> TILE_FALLBACK = new IdentityHashMap<>();

    private static Object lastLevelIdentity;
    private static long lastPruneNanos;

    private DisplayTextureCache() {}

    /**
     * Fills the render state's texture and UV range. No allocation occurs on the normal cached-tile path.
     */
    public static synchronized void prepare(DisplayBlockEntity display, DisplayWorldRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        syncLevel(minecraft);

        state.textureId = null;
        state.u0 = 0.0f;
        state.v0 = 0.0f;
        state.u1 = 1.0f;
        state.v1 = 1.0f;

        Level level = minecraft.level;
        if (level == null || display == null) return;

        long now = System.nanoTime();
        TileBinding binding = bindingFor(level, display, minecraft.getTextureManager(), now);
        if (binding != null) {
            WallEntry wall = binding.wall;
            wall.lastUsedNanos = now;
            updateWallTexture(level, wall, now);
            state.textureId = wall.id;
            state.u0 = binding.u0;
            state.v0 = binding.v0;
            state.u1 = binding.u1;
            state.v1 = binding.v1;
            return;
        }

        state.textureId = fallbackTextureFor(display, minecraft, now);
    }

    /**
     * Called once per client tick. Invisible/unloaded walls are evicted after a grace period; changing worlds
     * immediately releases every dynamic texture.
     */
    public static synchronized void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        syncLevel(minecraft);

        long now = System.nanoTime();
        if (now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
        lastPruneNanos = now;

        TextureManager manager = minecraft.getTextureManager();
        WallEntry[] walls = WALLS.toArray(WallEntry[]::new);
        for (WallEntry wall : walls) {
            if (now - wall.lastUsedNanos <= STALE_NANOS) continue;
            removeWall(wall, manager);
        }

        Iterator<Map.Entry<DisplayBlockEntity, TileEntry>> iterator = TILE_FALLBACK.entrySet().iterator();
        while (iterator.hasNext()) {
            TileEntry entry = iterator.next().getValue();
            if (now - entry.lastUsedNanos <= STALE_NANOS) continue;
            manager.release(entry.id);
            iterator.remove();
        }
    }

    public static synchronized void clearAll() {
        Minecraft minecraft = Minecraft.getInstance();
        clearAll(minecraft.getTextureManager());
        lastLevelIdentity = minecraft.level;
    }

    private static void syncLevel(Minecraft minecraft) {
        Object levelIdentity = minecraft.level;
        if (levelIdentity == lastLevelIdentity) return;
        clearAll(minecraft.getTextureManager());
        lastLevelIdentity = levelIdentity;
    }

    private static void clearAll(TextureManager manager) {
        for (WallEntry wall : WALLS) manager.release(wall.id);
        WALLS.clear();
        TILE_BINDINGS.clear();
        for (TileEntry entry : TILE_FALLBACK.values()) manager.release(entry.id);
        TILE_FALLBACK.clear();
        lastPruneNanos = 0L;
    }

    private static TileBinding bindingFor(Level level, DisplayBlockEntity display, TextureManager manager, long now) {
        long key = display.getBlockPos().asLong();
        TileBinding existing = TILE_BINDINGS.get(key);
        if (existing != null) {
            WallEntry wall = existing.wall;
            if (now - wall.lastTopologyCheckNanos <= TOPOLOGY_RECHECK_NANOS) return existing;

            WallGeometry geometry = collectWall(level, display.getBlockPos(), display.getBlockState());
            if (geometry != null && wall.matches(geometry)) {
                wall.lastTopologyCheckNanos = now;
                return existing;
            }

            removeWall(wall, manager);
        }

        WallGeometry geometry = collectWall(level, display.getBlockPos(), display.getBlockState());
        if (geometry == null || geometry.blocks.isEmpty()) return null;
        return createWallBinding(level, geometry, display.getBlockPos(), manager, now);
    }

    private static TileBinding createWallBinding(
            Level level,
            WallGeometry geometry,
            BlockPos requestedPos,
            TextureManager manager,
            long now
    ) {
        int columns = geometry.maxHorizontal - geometry.minHorizontal + 1;
        int rows = geometry.maxY - geometry.minY + 1;
        int textureWidth = columns * DisplayBlockEntity.MAX_WIDTH;
        int textureHeight = rows * DisplayBlockEntity.MAX_HEIGHT;
        if (textureWidth <= 0 || textureHeight <= 0
                || textureWidth > MAX_SHARED_TEXTURE_DIMENSION
                || textureHeight > MAX_SHARED_TEXTURE_DIMENSION) {
            return null;
        }

        long serial = NEXT_ID.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "dynamic/display_wall_" + serial);
        DynamicTexture texture = new DynamicTexture(
                "LogicSim display wall " + serial,
                textureWidth,
                textureHeight,
                false
        );
        manager.register(id, texture);

        BlockPos[] positions = new BlockPos[columns * rows];
        long[] revisions = new long[positions.length];
        Arrays.fill(revisions, Long.MIN_VALUE);
        WallEntry wall = new WallEntry(
                id,
                texture,
                geometry.facing,
                geometry.right,
                geometry.minHorizontal,
                geometry.maxHorizontal,
                geometry.minY,
                geometry.maxY,
                columns,
                rows,
                positions,
                revisions,
                geometry.blocks.size(),
                now
        );
        WALLS.add(wall);

        TileBinding requested = null;
        for (BlockPos pos : geometry.blocks) {
            int dx = pos.getX() - geometry.origin.getX();
            int dz = pos.getZ() - geometry.origin.getZ();
            int horizontal = dx * geometry.right.getStepX() + dz * geometry.right.getStepZ();
            int tileX = horizontal - geometry.minHorizontal;
            int tileY = geometry.maxY - pos.getY();
            if (tileX < 0 || tileX >= columns || tileY < 0 || tileY >= rows) continue;

            int tileIndex = tileY * columns + tileX;
            positions[tileIndex] = pos.immutable();
            TileBinding binding = new TileBinding(
                    wall,
                    tileX / (float) columns,
                    tileY / (float) rows,
                    (tileX + 1) / (float) columns,
                    (tileY + 1) / (float) rows
            );
            TILE_BINDINGS.put(pos.asLong(), binding);
            if (pos.equals(requestedPos)) requested = binding;
        }

        // Upload the authoritative current wall immediately. This also makes untouched black pixels opaque black.
        updateWallTexture(level, wall, Long.MAX_VALUE);
        return requested;
    }

    private static void updateWallTexture(Level level, WallEntry wall, long now) {
        if (now != Long.MAX_VALUE && now - wall.lastRevisionScanNanos < WALL_REVISION_SCAN_MIN_NANOS) return;
        wall.lastRevisionScanNanos = now == Long.MAX_VALUE ? System.nanoTime() : now;

        NativeImage image = wall.texture.getPixels();
        if (image == null) return;

        boolean upload = false;
        for (int tileIndex = 0; tileIndex < wall.positions.length; tileIndex++) {
            BlockPos pos = wall.positions[tileIndex];
            if (pos == null) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof DisplayBlockEntity display)) continue;

            long revision = display.framebuffer().revision();
            if (wall.revisions[tileIndex] == revision) continue;
            wall.revisions[tileIndex] = revision;

            int tileX = tileIndex % wall.columns;
            int tileY = tileIndex / wall.columns;
            copyTile(display.framebuffer(), image,
                    tileX * DisplayBlockEntity.MAX_WIDTH,
                    tileY * DisplayBlockEntity.MAX_HEIGHT);
            upload = true;
        }

        if (upload) wall.texture.upload();
    }

    private static void copyTile(DisplayFramebuffer framebuffer, NativeImage image, int baseX, int baseY) {
        for (int y = 0; y < DisplayBlockEntity.MAX_HEIGHT; y++) {
            int imageY = baseY + y;
            for (int x = 0; x < DisplayBlockEntity.MAX_WIDTH; x++) {
                image.setPixel(baseX + x, imageY, RGB565_TO_ARGB[framebuffer.pixelRgb565(x, y) & 0xFFFF]);
            }
        }
    }

    private static Identifier fallbackTextureFor(DisplayBlockEntity display, Minecraft minecraft, long now) {
        TileEntry entry = TILE_FALLBACK.get(display);
        if (entry == null) {
            long serial = NEXT_ID.incrementAndGet();
            Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "dynamic/display_tile_" + serial);
            DynamicTexture texture = new DynamicTexture(
                    "LogicSim display tile " + serial,
                    DisplayBlockEntity.MAX_WIDTH,
                    DisplayBlockEntity.MAX_HEIGHT,
                    false
            );
            minecraft.getTextureManager().register(id, texture);
            entry = new TileEntry(id, texture);
            TILE_FALLBACK.put(display, entry);
        }

        entry.lastUsedNanos = now;
        long revision = display.framebuffer().revision();
        if (entry.uploadedRevision != revision) {
            NativeImage image = entry.texture.getPixels();
            if (image != null) {
                copyTile(display.framebuffer(), image, 0, 0);
                entry.texture.upload();
            }
            entry.uploadedRevision = revision;
        }
        return entry.id;
    }

    private static void removeWall(WallEntry wall, TextureManager manager) {
        if (!WALLS.remove(wall)) return;
        manager.release(wall.id);
        for (BlockPos pos : wall.positions) {
            if (pos == null) continue;
            long key = pos.asLong();
            TileBinding binding = TILE_BINDINGS.get(key);
            if (binding != null && binding.wall == wall) TILE_BINDINGS.remove(key);
        }
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

        return new WallGeometry(start.immutable(), facing, right, blocks, minHorizontal, maxHorizontal, minY, maxY);
    }

    private static int[] buildRgb565Lookup() {
        int[] result = new int[1 << 16];
        for (int value = 0; value < result.length; value++) result[value] = DisplayFramebuffer.rgb565ToArgb(value);
        return result;
    }

    private static final class WallEntry {
        private final Identifier id;
        private final DynamicTexture texture;
        private final Direction facing;
        private final Direction right;
        private final int minHorizontal;
        private final int maxHorizontal;
        private final int minY;
        private final int maxY;
        private final int columns;
        private final int rows;
        private final BlockPos[] positions;
        private final long[] revisions;
        private final int blockCount;
        private long lastUsedNanos;
        private long lastTopologyCheckNanos;
        private long lastRevisionScanNanos = Long.MIN_VALUE;

        private WallEntry(
                Identifier id,
                DynamicTexture texture,
                Direction facing,
                Direction right,
                int minHorizontal,
                int maxHorizontal,
                int minY,
                int maxY,
                int columns,
                int rows,
                BlockPos[] positions,
                long[] revisions,
                int blockCount,
                long now
        ) {
            this.id = id;
            this.texture = texture;
            this.facing = facing;
            this.right = right;
            this.minHorizontal = minHorizontal;
            this.maxHorizontal = maxHorizontal;
            this.minY = minY;
            this.maxY = maxY;
            this.columns = columns;
            this.rows = rows;
            this.positions = positions;
            this.revisions = revisions;
            this.blockCount = blockCount;
            this.lastUsedNanos = now;
            this.lastTopologyCheckNanos = now;
        }

        private boolean matches(WallGeometry geometry) {
            if (geometry == null
                    || facing != geometry.facing
                    || right != geometry.right
                    || blockCount != geometry.blocks.size()) return false;
            int newColumns = geometry.maxHorizontal - geometry.minHorizontal + 1;
            int newRows = geometry.maxY - geometry.minY + 1;
            if (columns != newColumns || rows != newRows) return false;
            for (BlockPos pos : geometry.blocks) {
                TileBinding binding = TILE_BINDINGS.get(pos.asLong());
                if (binding == null || binding.wall != this) return false;
            }
            return true;
        }
    }

    private record TileBinding(WallEntry wall, float u0, float v0, float u1, float v1) {}
    private record WallGeometry(
            BlockPos origin,
            Direction facing,
            Direction right,
            Set<BlockPos> blocks,
            int minHorizontal,
            int maxHorizontal,
            int minY,
            int maxY
    ) {}

    private static final class TileEntry {
        private final Identifier id;
        private final DynamicTexture texture;
        private long uploadedRevision = Long.MIN_VALUE;
        private long lastUsedNanos;

        private TileEntry(Identifier id, DynamicTexture texture) {
            this.id = id;
            this.texture = texture;
        }
    }
}
