package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RealtimeDisplayNative64FastPath;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces only the full-density (64px/tile) realtime DISPLAY batch loop with a lower-overhead equivalent.
 * Lower densities retain their existing specialized paths unchanged.
 */
@Mixin(RealtimeDisplaySurface.Surface.class)
public abstract class RealtimeDisplaySurfaceNative64Mixin {
    @Unique private static final AtomicBoolean LOGIC_STREAM_LOGGED = new AtomicBoolean();

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

    @Unique
    private final RealtimeDisplayNative64FastPath.State logic$native64State = new RealtimeDisplayNative64FastPath.State();

    @Inject(method = "recordBatch", at = @At("HEAD"), cancellable = true)
    private void logic$recordNative64Batch(long[] raws, int count, CallbackInfo ci) {
        if (density != DisplayBlockEntity.MAX_WIDTH) return;

        RealtimeDisplayNative64FastPath.State state = logic$native64State;
        state.reset(nonZeroPixels, revision);
        RealtimeDisplayNative64FastPath.apply(
                raws,
                count,
                logicalWidth,
                logicalHeight,
                backingWidth,
                columns,
                pixels,
                tileRevisions,
                batchDirtyTileWords,
                state
        );

        if (state.variableColorStreaming() && LOGIC_STREAM_LOGGED.compareAndSet(false, true)) {
            LogicSimulationMod.LOGGER.info(
                    "[DISPLAY NATIVE64 STREAM] active=true mode=adaptive-branchless-rgb-v7 logical={}x{} batchCount={} nonZeroAccounting=branchless metadataCommit=whole-wall",
                    logicalWidth,
                    logicalHeight,
                    Math.min(count, raws == null ? 0 : raws.length)
            );
        }

        if (state.changed()) {
            nonZeroPixels = state.nonZeroPixels();
            revision = state.revision();
            publishedRevision = revision;
        }
        ci.cancel();
    }
}
