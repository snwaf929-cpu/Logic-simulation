package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded, document-level undo/redo history.
 *
 * <p>Call {@link #checkpoint(String, CircuitDocument)} immediately before an edit. No-op
 * checkpoints are discarded lazily while undoing, which makes it safe to checkpoint a possible
 * drag before knowing whether the pointer actually moved.</p>
 */
public final class EditorHistory {
    public static final int DEFAULT_LIMIT = 160;

    private final int limit;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();

    public EditorHistory() {
        this(DEFAULT_LIMIT);
    }

    public EditorHistory(int limit) {
        this.limit = Math.max(8, limit);
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }

    public void checkpoint(String label, CircuitDocument current) {
        if (current == null) return;
        CircuitDocument snapshot = EditorDocumentSnapshot.copy(current);
        if (!undo.isEmpty() && EditorDocumentSnapshot.same(undo.peekLast().document, snapshot)) return;
        undo.addLast(new Entry(cleanLabel(label), snapshot));
        while (undo.size() > limit) undo.removeFirst();
        redo.clear();
    }

    public Result undo(CircuitDocument current) {
        if (current == null) return null;
        CircuitDocument now = EditorDocumentSnapshot.copy(current);
        while (!undo.isEmpty()) {
            Entry target = undo.removeLast();
            if (EditorDocumentSnapshot.same(target.document, now)) continue;
            redo.addLast(new Entry(target.label, now));
            while (redo.size() > limit) redo.removeFirst();
            return new Result(target.label, EditorDocumentSnapshot.copy(target.document));
        }
        return null;
    }

    public Result redo(CircuitDocument current) {
        if (current == null || redo.isEmpty()) return null;
        Entry target = redo.removeLast();
        CircuitDocument now = EditorDocumentSnapshot.copy(current);
        undo.addLast(new Entry(target.label, now));
        while (undo.size() > limit) undo.removeFirst();
        return new Result(target.label, EditorDocumentSnapshot.copy(target.document));
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private static String cleanLabel(String label) {
        return label == null || label.isBlank() ? "Edit" : label.trim();
    }

    private record Entry(String label, CircuitDocument document) {}
    public record Result(String label, CircuitDocument document) {}
}
