package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(CircuitCanvasWidget.class)
public interface CanvasAccess {
    @Accessor("runtime") CompiledCircuit logic$getRuntime();
    @Accessor("runtime") void logic$setRuntime(CompiledCircuit runtime);
    @Accessor("runtimeRootDocument") CircuitDocument logic$getRuntimeRootDocument();
    @Accessor("chips") ClientChipLibrary logic$getChipLibrary();
    @Accessor("inputStates") Map<Integer, Long> logic$getInputStates();
}
