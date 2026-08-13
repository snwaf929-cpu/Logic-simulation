package com.foreverspark.logicsim.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A single 1-bit net in a circuit. */
public final class Signal {
    private final int id;
    private final String path;
    private final List<NandGate> consumers = new ArrayList<>();
    private LogicValue value;

    Signal(int id, String path, LogicValue initialValue) {
        this.id = id;
        this.path = path;
        this.value = initialValue;
    }

    public int id() {
        return id;
    }

    public String path() {
        return path;
    }

    public LogicValue value() {
        return value;
    }

    public List<NandGate> consumers() {
        return Collections.unmodifiableList(consumers);
    }

    void addConsumer(NandGate gate) {
        consumers.add(gate);
    }

    void setValue(LogicValue value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return path + "=" + value;
    }
}
