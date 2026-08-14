package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A realtime-mapped integrated-client wall owns transient VRAM in RealtimeDisplaySurface. Serializing the same 64x64
 * RGB565 tile framebuffer into every DisplayBlockEntity bloats 2K walls into multi-megabyte chunks and makes saving or
 * pausing the integrated server expensive. Skip only that redundant framebuffer payload while the local realtime map
 * exists. Unmapped displays and dedicated servers retain the normal persistence behavior.
 */
@Mixin(DisplayBlockEntity.class)
public abstract class DisplayRealtimePersistenceMixin {
    @WrapOperation(
            method = "saveAdditional",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/ValueOutput;putIntArray(Ljava/lang/String;[I)V"
            )
    )
    private void logic$skipRealtimeFramebufferNbt(
            ValueOutput output,
            String key,
            int[] value,
            Operation<Void> original
    ) {
        DisplayBlockEntity self = (DisplayBlockEntity) (Object) this;
        if ("framebuffer".equals(key) && RealtimeDisplaySurface.tileView(self.getBlockPos()) != null) {
            return;
        }
        original.call(output, key, value);
    }
}
