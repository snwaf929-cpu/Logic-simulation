package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.client.render.RealtimeDisplayBatch32FastPath;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 32-pixels-per-tile realtime framebuffer specialization.
 *
 * <p>The simulator already submits pixel commands in large batches. At density 32 every logical pixel maps to a 2x2
 * backing rectangle, but the generic path still incremented revision metadata per command. This mixin applies the
 * complete batch with one revision publication and one dirty-tile commit.</p>
 */
@Mixin(RealtimeDisplaySurface.Surface.class)
public abstract class RealtimeDisplaySurfaceBatch32Mixin {
    @Shadow @Final private int density;
    @Shadow @Final private int logicalWidth;
    @Shadow @Final private int logicalHeight;
    @Shadow @Final private int backingWidth;
    @Shadow @Final private int columns;
    @Shadow @Final private char[] pixels;
    @Shadow @Final private long[] tileRevisions;
    @Shadow @Final private long[] batchDirtyTileWords;
    @Shadow private long revision;
    @Shadow private volatile long publishedRevision;
    @Shadow private int nonZeroPixels;

    @Unique private final RealtimeDisplayBatch32FastPath.State logic$batch32State =
            new RealtimeDisplayBatch32FastPath.State();
    @Unique private boolean logic$batch32Logged;

    @Inject(method = "recordBatch", at = @At("HEAD"), cancellable = true)
    private void logic$recordBatch32(long[] raws, int count, CallbackInfo ci) {
        if (density != 32 || raws == null || count <= 0) return;

        if (!logic$batch32Logged) {
            logic$batch32Logged = true;
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY BATCH32] active=true logical={}x{} backingWidth={} columns={} scale=2 metadataCommit=batch",
                    logicalWidth,
                    logicalHeight,
                    backingWidth,
                    columns
            );
        }

        logic$batch32State.reset(nonZeroPixels, revision);
        RealtimeDisplayBatch32FastPath.apply(
                raws,
                count,
                logicalWidth,
                logicalHeight,
                backingWidth,
                columns,
                pixels,
                tileRevisions,
                batchDirtyTileWords,
                logic$batch32State
        );
        nonZeroPixels = logic$batch32State.nonZeroPixels();
        revision = logic$batch32State.revision();
        if (logic$batch32State.changed()) publishedRevision = revision;
        ci.cancel();
    }
}
