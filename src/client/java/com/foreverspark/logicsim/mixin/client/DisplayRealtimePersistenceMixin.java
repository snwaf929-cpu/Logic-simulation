package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import com.foreverspark.logicsim.client.render.RealtimeDisplaySurface;
import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Integrated-client persistence/synchronization support for realtime Pixel Display walls.
 *
 * Large realtime walls do not need the same 64x64 framebuffer serialized into every physical tile. The realtime
 * surface is the transient pixel source of truth. Small control state still has to cross the server -> client block
 * entity update, however. In particular the renderer gates realtime pixels on wallPowered; without explicitly syncing
 * that flag the client-side DisplayBlockEntity resets it to false in loadAdditional() and every mapped wall renders
 * black even while the integrated server knows the wall is powered.
 */
@Mixin(DisplayBlockEntity.class)
public abstract class DisplayRealtimePersistenceMixin {
    private static final String LOGIC_WALL_POWERED_KEY = "logicRealtimeWallPowered";

    @Shadow private boolean wallPowered;
    @Unique private boolean logic$wasRealtimeWallPowered;

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

    /**
     * saveWithoutMetadata() is also used for the block-entity update tag, so this single boolean reaches the local
     * client without restoring the expensive per-tile framebuffer payload.
     */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void logic$saveRealtimeWallPower(ValueOutput output, CallbackInfo ci) {
        output.putBoolean(LOGIC_WALL_POWERED_KEY, wallPowered);
    }

    /** Preserve the previous client-visible power state before DisplayBlockEntity resets transient state on load. */
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void logic$capturePreviousRealtimeWallPower(ValueInput input, CallbackInfo ci) {
        logic$wasRealtimeWallPowered = wallPowered;
    }

    /**
     * Restore after DisplayBlockEntity's own loadAdditional() resets transient power bookkeeping to false.
     * A redstone OFF -> ON transition starts a fresh display session: clear the shared realtime framebuffer once on
     * the wall controller so pixels accumulated while hidden/off can never reappear when power comes back.
     */
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void logic$loadRealtimeWallPower(ValueInput input, CallbackInfo ci) {
        boolean powered = input.getBooleanOr(LOGIC_WALL_POWERED_KEY, false);
        wallPowered = powered;

        if (!powered || logic$wasRealtimeWallPowered) return;
        DisplayBlockEntity self = (DisplayBlockEntity) (Object) this;
        RealtimeDisplaySurface.TileView tile = RealtimeDisplaySurface.tileView(self.getBlockPos());
        if (tile == null) return;
        RealtimeDisplaySurface.Surface surface = tile.surface();
        if (!self.getBlockPos().equals(surface.controllerPos())) return;

        // Reuse the exact DATA64 CLEAR implementation so revision/texture invalidation stays identical to a command.
        surface.record(DisplayCommandCodec.clear());
    }
}