package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Batch-scoped access only; the fused writer still has exactly one simulation-thread writer. */
@Mixin(RealtimeDisplaySurface.Surface.class)
public interface RealtimeDisplaySurfaceAccess {
    @Accessor("pixels")
    char[] logic$getPixels();

    @Accessor("tileRevisions")
    long[] logic$getTileRevisions();

    @Accessor("revision")
    long logic$getRevision();

    @Accessor("revision")
    void logic$setRevision(long value);

    @Accessor("publishedRevision")
    void logic$setPublishedRevision(long value);

    @Accessor("nonZeroPixels")
    int logic$getNonZeroPixels();

    @Accessor("nonZeroPixels")
    void logic$setNonZeroPixels(int value);
}
