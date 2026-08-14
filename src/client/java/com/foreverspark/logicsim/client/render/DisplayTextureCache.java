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

/**
 * GPU texture cache for physical Pixel Display tiles.
 *
 * One 64x64 DynamicTexture represents one display block. The texture is uploaded only when that tile's framebuffer
 * revision changes. Rendering a dense/random tile is therefore always one quad, rather than up to 4096 colored
 * quads reconstructed from the block entity every frame.
 */
public final class DisplayTextureCache {
    private static final long STALE_NANOS = 10_000_000_000L;
    private static final long PRUNE_INTERVAL_NANOS = 1_000_000_000L;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<DisplayBlockEntity, Entry> ENTRIES = new IdentityHashMap<>();

    private static Object lastLevelIdentity;
    private static long lastPruneNanos;

    private DisplayTextureCache() {}

    /** Returns the GPU texture containing the tile's authoritative 64x64 backing framebuffer. */
    public static synchronized Identifier textureFor(DisplayBlockEntity display) {
        Minecraft minecraft = Minecraft.getInstance();
        Object levelIdentity = minecraft.level;
        if (levelIdentity != lastLevelIdentity) {
            clearAll(minecraft.getTextureManager());
            lastLevelIdentity = levelIdentity;
        }

        long now = System.nanoTime();
        Entry entry = ENTRIES.get(display);
        if (entry == null) {
            long serial = NEXT_ID.incrementAndGet();
            Identifier id = Identifier.fromNamespaceAndPath(
                    LogicSimulationMod.MOD_ID,
                    "dynamic/display_" + serial
            );
            DynamicTexture texture = new DynamicTexture("LogicSim display " + serial, DisplayBlockEntity.MAX_WIDTH,
                    DisplayBlockEntity.MAX_HEIGHT, false);
            minecraft.getTextureManager().register(id, texture);
            entry = new Entry(id, texture);
            ENTRIES.put(display, entry);
        }

        entry.lastUsedNanos = now;
        long revision = display.framebuffer().revision();
        if (entry.uploadedRevision != revision) {
            upload(display, entry);
            entry.uploadedRevision = revision;
        }
        return entry.id;
    }

    /**
     * Called once per client tick. Invisible/unloaded tiles are evicted after a short grace period, and changing
     * worlds immediately releases every dynamic texture.
     */
    public static synchronized void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        Object levelIdentity = minecraft.level;
        if (levelIdentity != lastLevelIdentity) {
            clearAll(minecraft.getTextureManager());
            lastLevelIdentity = levelIdentity;
        }

        long now = System.nanoTime();
        if (now - lastPruneNanos < PRUNE_INTERVAL_NANOS) return;
        lastPruneNanos = now;

        TextureManager manager = minecraft.getTextureManager();
        Iterator<Map.Entry<DisplayBlockEntity, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.lastUsedNanos <= STALE_NANOS) continue;
            manager.release(entry.id);
            iterator.remove();
        }
    }

    public static synchronized void clearAll() {
        clearAll(Minecraft.getInstance().getTextureManager());
        lastLevelIdentity = Minecraft.getInstance().level;
    }

    private static void clearAll(TextureManager manager) {
        for (Entry entry : ENTRIES.values()) manager.release(entry.id);
        ENTRIES.clear();
        lastPruneNanos = 0L;
    }

    private static void upload(DisplayBlockEntity display, Entry entry) {
        NativeImage image = entry.texture.getPixels();
        if (image == null) return;

        DisplayFramebuffer framebuffer = display.framebuffer();
        for (int y = 0; y < DisplayBlockEntity.MAX_HEIGHT; y++) {
            for (int x = 0; x < DisplayBlockEntity.MAX_WIDTH; x++) {
                image.setPixel(x, y, DisplayFramebuffer.rgb565ToArgb(framebuffer.pixelRgb565(x, y)));
            }
        }
        entry.texture.upload();
    }

    private static final class Entry {
        private final Identifier id;
        private final DynamicTexture texture;
        private long uploadedRevision = Long.MIN_VALUE;
        private long lastUsedNanos;

        private Entry(Identifier id, DynamicTexture texture) {
            this.id = id;
            this.texture = texture;
        }
    }
}
