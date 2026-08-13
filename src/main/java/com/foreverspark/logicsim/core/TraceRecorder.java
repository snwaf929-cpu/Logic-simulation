package com.foreverspark.logicsim.core;

import java.util.ArrayList;
import java.util.List;

/** Fixed-size in-memory flight recorder. Old events are overwritten when full. */
public final class TraceRecorder {
    private final TraceEvent[] events;
    private int writeIndex;
    private int size;

    public TraceRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.events = new TraceEvent[capacity];
    }

    public void record(TraceEvent event) {
        events[writeIndex] = event;
        writeIndex = (writeIndex + 1) % events.length;
        if (size < events.length) {
            size++;
        }
    }

    public int capacity() {
        return events.length;
    }

    public int size() {
        return size;
    }

    public List<TraceEvent> snapshot() {
        List<TraceEvent> result = new ArrayList<>(size);
        int start = (writeIndex - size + events.length) % events.length;
        for (int i = 0; i < size; i++) {
            result.add(events[(start + i) % events.length]);
        }
        return List.copyOf(result);
    }

    public void clear() {
        writeIndex = 0;
        size = 0;
    }
}
