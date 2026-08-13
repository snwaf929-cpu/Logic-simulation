package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CircuitCanvasWidget.class)
public interface CanvasAccess {
    @Accessor("runtime") CompiledCircuit logic$getRuntime();
    @Accessor("runtimeRootDocument") CircuitDocument logic$getRuntimeRootDocument();
    @Accessor("chips") ClientChipLibrary logic$getChipLibrary();
}
