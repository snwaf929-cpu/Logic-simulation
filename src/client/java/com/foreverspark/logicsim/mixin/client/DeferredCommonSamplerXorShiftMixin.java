package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.LogicSimulationMod;
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
 * v9 replacement for the deferred DISPLAY common-probability sampler.
 *
 * <p>The 2K stress board clocks 22 coordinate RANDOM lanes every virtual cycle. Those lanes use only the established
 * 0/25/50/75/100% common probabilities, but the previous sampler generated each 64-bit plane with a xoroshiro-style
 * two-word state transition containing several rotates and an add. RANDOM output sequences are intentionally not a
 * stable external API, so this hot path may use a cheaper full-period xorshift64 state while preserving the exact
 * bitwise probability construction: one unbiased word for 50%, two ANDed words for 25%, and two ORed words for 75%.</p>
 */
@Mixin(targets = "com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$CommonSampler", remap = false)
public abstract class DeferredCommonSamplerXorShiftMixin {
    @Unique private static final long LOGIC_NONZERO_STATE = 0xD1B54A32D192ED03L;
    @Unique private static final AtomicBoolean LOGIC_LOGGED = new AtomicBoolean();

    @Shadow @Final private long outputMask;
    @Shadow @Final private long chance25Mask;
    @Shadow @Final private long chance50Mask;
    @Shadow @Final private long chance75Mask;
    @Shadow @Final private long chance100Mask;
    @Shadow @Final private long activeMask;
    @Shadow @Final private boolean needsSecondWord;

    @Unique private long logic$xorshiftState;

    @Inject(method = "<init>(JJJJJJ)V", at = @At("TAIL"))
    private void logic$initXorShift64(
            long outputMask,
            long chance25Mask,
            long chance50Mask,
            long chance75Mask,
            long chance100Mask,
            long seed,
            CallbackInfo ci
    ) {
        long mixed = logic$mix64(seed ^ 0xA0761D6478BD642FL);
        logic$xorshiftState = mixed == 0L ? LOGIC_NONZERO_STATE : mixed;

        if (outputMask != 0L && LOGIC_LOGGED.compareAndSet(false, true)) {
            LogicSimulationMod.LOGGER.info(
                    "[CLOCK XY SAMPLER] active=true mode=xorshift64-bitplanes-v9 lanes={} rngWordsPerCycle={} probabilities=0/25/50/75/100 callbackFree=true",
                    Long.bitCount(outputMask),
                    needsSecondWord ? 2 : (activeMask == 0L ? 0 : 1)
            );
        }
    }

    /**
     * @author ForeverSpArK / OpenAI
     * @reason This sampler runs once per virtual display clock cycle; preserve exact common probability semantics with
     * a lower-instruction full-period PRNG transition.
     */
    @Overwrite
    private long sample() {
        long result = chance100Mask;
        if (activeMask != 0L) {
            long r0 = logic$nextWord();
            result |= r0 & chance50Mask;
            if (needsSecondWord) {
                long r1 = logic$nextWord();
                result |= (r0 & r1) & chance25Mask;
                result |= (r0 | r1) & chance75Mask;
            }
        }
        return result & outputMask;
    }

    @Unique
    private long logic$nextWord() {
        long x = logic$xorshiftState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        if (x == 0L) x = LOGIC_NONZERO_STATE;
        logic$xorshiftState = x;
        return x;
    }

    @Unique
    private static long logic$mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
