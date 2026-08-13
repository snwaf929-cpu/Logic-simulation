package com.foreverspark.logicsim.mixin;

import com.foreverspark.logicsim.block.DisplayBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DisplayBlockEntity.class)
public interface DisplayBlockEntityAccessor {
    @Accessor("busX") int logic$getBusX();
    @Accessor("busX") void logic$setBusX(int value);
    @Accessor("busY") int logic$getBusY();
    @Accessor("busY") void logic$setBusY(int value);
    @Accessor("busColor") int logic$getBusColor();
    @Accessor("busColor") void logic$setBusColor(int value);
    @Accessor("busWrite") boolean logic$getBusWrite();
    @Accessor("busWrite") void logic$setBusWrite(boolean value);
    @Accessor("busClear") boolean logic$getBusClear();
    @Accessor("busClear") void logic$setBusClear(boolean value);
    @Accessor("syncPending") boolean logic$getSyncPending();
    @Accessor("syncPending") void logic$setSyncPending(boolean value);
}
