package com.foreverspark.logicsim.editor.model;

@FunctionalInterface
public interface ChipLookup {
    ChipDefinition find(String name);

    static ChipLookup empty() {
        return name -> null;
    }
}
