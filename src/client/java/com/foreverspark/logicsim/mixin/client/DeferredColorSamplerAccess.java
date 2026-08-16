package com.foreverspark.logicsim.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Direct, callback-free bridge to the v6 COLOR sampler overwrite. */
@Mixin(targets = "com.foreverspark.logicsim.client.render.DeferredColorRandomDisplayFastPath$ColorSampler", remap = false)
public interface DeferredColorSamplerAccess {
    @Invoker("sample")
    long logic$sampleColor();
}
