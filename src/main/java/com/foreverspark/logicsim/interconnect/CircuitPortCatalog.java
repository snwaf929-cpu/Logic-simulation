package com.foreverspark.logicsim.interconnect;

import com.foreverspark.logicsim.editor.model.PortSpec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

public final class CircuitPortCatalog {
    private static final Gson GSON = new GsonBuilder().create();

    public String circuitName = "";
    public List<PortSpec> inputs = List.of();
    public List<PortSpec> outputs = List.of();

    public CircuitPortCatalog() {}

    public CircuitPortCatalog(String circuitName, List<PortSpec> inputs, List<PortSpec> outputs) {
        this.circuitName = circuitName == null ? "" : circuitName;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    public String toJson() { return GSON.toJson(this); }

    public static CircuitPortCatalog fromJson(String json) {
        CircuitPortCatalog catalog = GSON.fromJson(json, CircuitPortCatalog.class);
        if (catalog == null) throw new IllegalArgumentException("Port catalog is invalid");
        if (catalog.circuitName == null) catalog.circuitName = "";
        if (catalog.inputs == null) catalog.inputs = List.of();
        if (catalog.outputs == null) catalog.outputs = List.of();
        return catalog;
    }
}
