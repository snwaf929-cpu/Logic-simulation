package com.foreverspark.logicsim.editor.runtime;

import com.foreverspark.logicsim.editor.model.ChipLookup;
import com.foreverspark.logicsim.editor.model.CircuitDocument;

public final class CircuitCompiler {
    private CircuitCompiler() {}

    public static CompiledCircuit compile(CircuitDocument document, ChipLookup chips) {
        return CircuitCompilerEngine.compile(document, chips);
    }
}
