package com.foreverspark.logicsim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Direct, callback-free bridge to the v5 common RANDOM sampler. */
@Mixin(targets = "com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$CommonSampler", remap = false)
public interface DeferredCommonSamplerAccess {
    @Invoker("sample")
    long logic$sampleCommon();
}
