package com.foreverspark.logicsim.client.render;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** GPU cache for the integrated-client RealtimeDisplaySurface fast path. */
public final class RealtimeDisplayTextureCache {
    private static final long STALE_NANOS = 10_000_000_000L;
    private static final long PRUNE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long SMALL_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L;
    private static final long LARGE_FRAME_INTERVAL_NANOS = 1_000_000_000L / 30L;
    private static final int LARGE_SURFACE_PIXELS = 2_000_000;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int[] RGB565_TO_ARGB = buildRgb565Lookup();
    private static final Map<RealtimeDisplaySurface.Surface, Entry> ENTRIES = new IdentityHashMap<>();

    private static Object lastLevelIdentity;
    private static long lastPruneNanos;

    private RealtimeDisplayTextureCache() {}

    /**
     * Returns true when this display tile belongs to a local realtime surface. The caller must not fall back to the
     * block-entity framebuffer in that case because the realtime surface is the newer source of truth.
     */
    public static synchronized boolean prepare(DisplayBlockEntity display, DisplayWorldRenderState state) {
        Minecraft minecraft = Minecraft.getInstance();
        syncLevel(minecraft);

        RealtimeDisplaySurface.TileView view = RealtimeDisplaySurface.tileView(display.getBlockPos());
        if (view == null) return false;

        RealtimeDisplaySurface.Surface surface = view.surface();
        state.hasPixels = surface.nonZeroPixels() > 0;
        if (!state.hasPixels) {
            state.textureId = null;
            state.u0 = state.v0 = 0.0f;
            state.u1 = state.v1 = 1.0f;
            return true;
        }

        long now = System.nanoTime();
        Entry entry = ENTRIES.get(surface);
        if (entry == null) {
            entry = createEntry(surface, minecraft.getTextureManager(), now);
            ENTRIES.put(surface, entry);
        }
        entry.lastUsedNanos = now;

        long published = surface.publishedRevision();
        long minInterval = (long) surface.backingWidth() * surface.backingHeight() >= LARGE_SURFACE_PIXELS
                ? LARGE_FRAME_INTERVAL_NANOS
                : SMALL_FRAME_INTERVAL_NANOS;
        if (published != entry.uploadedPublishedRevision
                && (entry.lastUploadNanos == 0L || now - entry.lastUploadNanos >= minInterval)) {
            updateTexture(surface, entry, published, now);
        }

        state.textureId = entry.id;
        state.u0 = view.tileX() / (float) surface.columns();
        state.v0 = view.tileY() / (float) surface.rows();
        state.u1 = (view.tileX() + 1) / (float) surface.columns();
        state.v1 = (view.tileY() + 1) / (float) surface.rows();
        return true;
    }

    public static synchronized void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        syncLevel(minecraft);
        long now = System.nanoTime();
        if (now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
        lastPruneNanos = now;

        TextureManager manager = minecraft.getTextureManager();
        Iterator<Map.Entry<RealtimeDisplaySurface.Surface, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
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
        for (Entry entry : ENTRIES.values()) manager.release(entry.id);
        ENTRIES.clear();
        lastPruneNanos = 0L;
    }

    private static Entry createEntry(RealtimeDisplaySurface.Surface surface, TextureManager manager, long now) {
        long serial = NEXT_ID.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath(LogicSimulationMod.MOD_ID, "dynamic/realtime_display_" + serial);
        DynamicTexture texture = new DynamicTexture(
                "LogicSim realtime display " + serial,
                surface.backingWidth(),
                surface.backingHeight(),
                false
        );
        manager.register(id, texture);
        return new Entry(id, texture, new long[surface.tileSlots()], now);
    }

    private static void updateTexture(
            RealtimeDisplaySurface.Surface surface,
            Entry entry,
            long published,
            long now
    ) {
        NativeImage image = entry.texture.getPixels();
        if (image == null) return;

        boolean changed = false;
        int columns = surface.columns();
        for (int tileIndex = 0; tileIndex < surface.tileSlots(); tileIndex++) {
            long revision = surface.tileRevision(tileIndex);
            if (revision == 0L || entry.tileRevisions[tileIndex] == revision) continue;
            entry.tileRevisions[tileIndex] = revision;

            int tileX = tileIndex % columns;
            int tileY = tileIndex / columns;
            copyTile(surface, image, tileX * DisplayBlockEntity.MAX_WIDTH, tileY * DisplayBlockEntity.MAX_HEIGHT);
            changed = true;
        }

        if (changed) entry.texture.upload();
        entry.uploadedPublishedRevision = published;
        entry.lastUploadNanos = now;
    }

    private static void copyTile(
            RealtimeDisplaySurface.Surface surface,
            NativeImage image,
            int baseX,
            int baseY
    ) {
        for (int y = 0; y < DisplayBlockEntity.MAX_HEIGHT; y++) {
            int sourceY = baseY + y;
            for (int x = 0; x < DisplayBlockEntity.MAX_WIDTH; x++) {
                int rgb565 = surface.pixelRgb565(baseX + x, sourceY) & 0xFFFF;
                image.setPixel(baseX + x, sourceY, RGB565_TO_ARGB[rgb565]);
            }
        }
    }

    private static int[] buildRgb565Lookup() {
        int[] result = new int[1 << 16];
        for (int value = 0; value < result.length; value++) result[value] = DisplayFramebuffer.rgb565ToArgb(value);
        return result;
    }

    private static final class Entry {
        private final Identifier id;
        private final DynamicTexture texture;
        private final long[] tileRevisions;
        private long uploadedPublishedRevision = Long.MIN_VALUE;
        private long lastUploadNanos;
        private long lastUsedNanos;

        private Entry(Identifier id, DynamicTexture texture, long[] tileRevisions, long now) {
            this.id = id;
            this.texture = texture;
            this.tileRevisions = tileRevisions;
            this.lastUsedNanos = now;
        }
    }
}
