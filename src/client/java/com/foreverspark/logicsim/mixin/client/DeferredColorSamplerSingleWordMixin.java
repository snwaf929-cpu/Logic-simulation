package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
import com.foreverspark.logicsim.client.render.SingleWordRgbMaskSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v6 hot-loop replacement for DeferredColorRandomDisplayFastPath.ColorSampler.
 *
 * <p>Arbitrary RGB probabilities are compiled to a cache-sized mask table. Runtime cost for all arbitrary COLOR lanes
 * is one xorshift32 state transition plus one table load, instead of one packed byte comparison/RNG word per eight
 * lanes. Common 25/50/75/100% lanes retain the established bitwise sampler semantics.</p>
 *
 * <p>The sample method is overwritten directly rather than injected with a cancellable callback. This method can run
 * tens of millions of times per second, so CallbackInfoReturnable construction/return boxing must not exist in the
 * RGB hot loop.</p>
 */
@Mixin(targets = "com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$ColorSampler", remap = false)
public abstract class DeferredColorSamplerSingleWordMixin {
    @Unique private static final long LOGIC_RNG_NONZERO_FALLBACK = 0x9E3779B97F4A7C15L;
    @Unique private static final long LOGIC_RNG_SEED_GAMMA = 0x9E3779B97F4A7C15L;
    @Unique private static final AtomicBoolean LOGIC_LOGGED = new AtomicBoolean();

    @Shadow @Final private long outputMask;
    @Shadow @Final private long chance25Mask;
    @Shadow @Final private long chance50Mask;
    @Shadow @Final private long chance75Mask;
    @Shadow @Final private long chance100Mask;
    @Shadow @Final private long activeCommonMask;
    @Shadow @Final private boolean needsSecondCommonWord;

    @Unique private SingleWordRgbMaskSampler logic$singleWordSampler;
    @Unique private long logic$rng0;
    @Unique private long logic$rng1;

    @Inject(method = "<init>(JJJJJJ[JJ)V", at = @At("TAIL"))
    private void logic$compileSingleWordMask(
            long outputMask,
            long chance25Mask,
            long chance50Mask,
            long chance75Mask,
            long chance100Mask,
            long arbitraryMask,
            long[] thresholdBitMasks,
            long seed,
            CallbackInfo ci
    ) {
        logic$singleWordSampler = new SingleWordRgbMaskSampler(
                arbitraryMask,
                thresholdBitMasks,
                seed ^ 0xD1B54A32D192ED03L
        );
        logic$rng0 = logic$mix64(seed);
        logic$rng1 = logic$mix64(seed + LOGIC_RNG_SEED_GAMMA);
        if ((logic$rng0 | logic$rng1) == 0L) logic$rng1 = LOGIC_RNG_NONZERO_FALLBACK;

        if (arbitraryMask != 0L && LOGIC_LOGGED.compareAndSet(false, true)) {
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK RGB SAMPLER] active=true mode=single-word-32bit-mask-v6-direct arbitraryLanes={} tableEntries={} compact16={} rngWordsPerColor={} probabilityQuantum=1/256 callbackFree=true",
                    logic$singleWordSampler.laneCount(),
                    logic$singleWordSampler.tableEntries(),
                    logic$singleWordSampler.compact16(),
                    logic$singleWordSampler.rngWordsPerSample()
            );
        }
    }

    /**
     * @author ForeverSpArK / OpenAI
     * @reason RGB sampling is a measured MHz hot loop; use the compiled one-word mask directly with no callback object.
     */
    @Overwrite
    private long sample() {
        SingleWordRgbMaskSampler sampler = logic$singleWordSampler;
        if (sampler == null) {
            throw new IllegalStateException("Single-word RGB sampler was not initialized");
        }

        long result = chance100Mask;
        if (activeCommonMask != 0L) {
            long r0 = logic$nextLong();
            result |= r0 & chance50Mask;
            if (needsSecondCommonWord) {
                long r1 = logic$nextLong();
                result |= (r0 & r1) & chance25Mask;
                result |= (r0 | r1) & chance75Mask;
            }
        }
        result |= sampler.sampleMask();
        return result & outputMask;
    }

    @Unique
    private long logic$nextLong() {
        long s0 = logic$rng0;
        long s1 = logic$rng1;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        logic$rng0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        logic$rng1 = Long.rotateLeft(s1, 28);
        return result;
    }

    @Unique
    private static long logic$mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
