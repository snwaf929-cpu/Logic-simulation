package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded, atomic document-level undo/redo history. */
public final class EditorHistory {
    public static final int DEFAULT_LIMIT = 160;

    private final int limit;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();
    private Entry pending;

    public EditorHistory() {
        this(DEFAULT_LIMIT);
    }

    public EditorHistory(int limit) {
        this.limit = Math.max(8, limit);
    }

    public void clear() {
        undo.clear();
        redo.clear();
        pending = null;
    }

    /** Starts one edit transaction. Repeated calls before commit keep the oldest pre-edit state. */
    public void checkpoint(String label, CircuitDocument current) {
        if (current == null || pending != null) return;
        pending = new Entry(cleanLabel(label), EditorDocumentSnapshot.copy(current));
    }

    /** Commits the pending transaction only if the document actually changed. */
    public boolean commit(CircuitDocument current) {
        if (pending == null || current == null) return false;
        Entry before = pending;
        pending = null;
        if (EditorDocumentSnapshot.same(before.document, current)) return false;
        pushUndo(before);
        redo.clear();
        return true;
    }

    public Result undo(CircuitDocument current) {
        if (current == null) return null;
        commit(current);
        if (undo.isEmpty()) return null;
        Entry target = undo.removeLast();
        redo.addLast(new Entry(target.label, EditorDocumentSnapshot.copy(current)));
        trim(redo);
        return new Result(target.label, EditorDocumentSnapshot.copy(target.document));
    }

    public Result redo(CircuitDocument current) {
        if (current == null || redo.isEmpty()) return null;
        pending = null;
        Entry target = redo.removeLast();
        pushUndo(new Entry(target.label, EditorDocumentSnapshot.copy(current)));
        return new Result(target.label, EditorDocumentSnapshot.copy(target.document));
    }

    public boolean canUndo() {
        return pending != null || !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private void pushUndo(Entry entry) {
        if (!undo.isEmpty() && EditorDocumentSnapshot.same(undo.peekLast().document, entry.document)) return;
        undo.addLast(entry);
        trim(undo);
    }

    private void trim(Deque<Entry> entries) {
        while (entries.size() > limit) entries.removeFirst();
    }

    private static String cleanLabel(String label) {
        return label == null || label.isBlank() ? "Edit" : label.trim();
    }

    private record Entry(String label, CircuitDocument document) {}
    public record Result(String label, CircuitDocument document) {}
}
