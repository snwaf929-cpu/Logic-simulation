package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.EditorNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Pure Phase 6 layout operations shared by the canvas and dependency-light regression checks.
 * These helpers only change editor geometry; they never alter circuit connectivity or logic semantics.
 */
public final class EditorLayoutTools {
    private EditorLayoutTools() {}

    public enum Alignment {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
        CENTER_X,
        CENTER_Y
    }

    public enum Axis {
        HORIZONTAL,
        VERTICAL
    }

    public static boolean align(List<EditorNode> nodes,
                                Alignment alignment,
                                ToDoubleFunction<EditorNode> width,
                                ToDoubleFunction<EditorNode> height) {
        if (nodes == null || nodes.size() < 2 || alignment == null) return false;
        requireDimensions(width, height);

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxRight = Double.NEGATIVE_INFINITY;
        double maxBottom = Double.NEGATIVE_INFINITY;
        for (EditorNode node : nodes) {
            if (node == null) continue;
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxRight = Math.max(maxRight, node.x + positive(width.applyAsDouble(node)));
            maxBottom = Math.max(maxBottom, node.y + positive(height.applyAsDouble(node)));
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxRight) || !Double.isFinite(maxBottom)) return false;

        double centerX = (minX + maxRight) * 0.5;
        double centerY = (minY + maxBottom) * 0.5;
        boolean changed = false;
        for (EditorNode node : nodes) {
            if (node == null) continue;
            double nextX = node.x;
            double nextY = node.y;
            switch (alignment) {
                case LEFT -> nextX = minX;
                case RIGHT -> nextX = maxRight - positive(width.applyAsDouble(node));
                case TOP -> nextY = minY;
                case BOTTOM -> nextY = maxBottom - positive(height.applyAsDouble(node));
                case CENTER_X -> nextX = centerX - positive(width.applyAsDouble(node)) * 0.5;
                case CENTER_Y -> nextY = centerY - positive(height.applyAsDouble(node)) * 0.5;
            }
            nextX = EditorGrid.snap(nextX);
            nextY = EditorGrid.snap(nextY);
            if (Double.compare(node.x, nextX) != 0 || Double.compare(node.y, nextY) != 0) {
                node.x = nextX;
                node.y = nextY;
                changed = true;
            }
        }
        return changed;
    }

    public static boolean alignPinRows(List<EditorNode> nodes, ToDoubleFunction<EditorNode> firstPinY) {
        if (nodes == null || nodes.size() < 2 || firstPinY == null) return false;
        double target = Double.POSITIVE_INFINITY;
        for (EditorNode node : nodes) {
            if (node == null) continue;
            target = Math.min(target, firstPinY.applyAsDouble(node));
        }
        if (!Double.isFinite(target)) return false;
        target = EditorGrid.snap(target);

        boolean changed = false;
        for (EditorNode node : nodes) {
            if (node == null) continue;
            double anchor = firstPinY.applyAsDouble(node);
            if (!Double.isFinite(anchor)) continue;
            double nextY = EditorGrid.snap(node.y + (target - anchor));
            if (Double.compare(node.y, nextY) != 0) {
                node.y = nextY;
                changed = true;
            }
        }
        return changed;
    }

    public static boolean distribute(List<EditorNode> nodes,
                                     Axis axis,
                                     ToDoubleFunction<EditorNode> width,
                                     ToDoubleFunction<EditorNode> height) {
        if (nodes == null || nodes.size() < 3 || axis == null) return false;
        requireDimensions(width, height);

        List<EditorNode> ordered = new ArrayList<>();
        for (EditorNode node : nodes) if (node != null) ordered.add(node);
        if (ordered.size() < 3) return false;

        if (axis == Axis.HORIZONTAL) {
            ordered.sort(Comparator.comparingDouble((EditorNode node) -> node.x).thenComparingInt(node -> node.id));
            EditorNode first = ordered.getFirst();
            EditorNode last = ordered.getLast();
            double start = first.x;
            double end = last.x + positive(width.applyAsDouble(last));
            double totalSize = 0.0;
            for (EditorNode node : ordered) totalSize += positive(width.applyAsDouble(node));
            double gap = (end - start - totalSize) / (ordered.size() - 1);
            double cursor = start + positive(width.applyAsDouble(first)) + gap;
            boolean changed = false;
            for (int i = 1; i < ordered.size() - 1; i++) {
                EditorNode node = ordered.get(i);
                double next = EditorGrid.snap(cursor);
                if (Double.compare(node.x, next) != 0) {
                    node.x = next;
                    changed = true;
                }
                cursor += positive(width.applyAsDouble(node)) + gap;
            }
            return changed;
        }

        ordered.sort(Comparator.comparingDouble((EditorNode node) -> node.y).thenComparingInt(node -> node.id));
        EditorNode first = ordered.getFirst();
        EditorNode last = ordered.getLast();
        double start = first.y;
        double end = last.y + positive(height.applyAsDouble(last));
        double totalSize = 0.0;
        for (EditorNode node : ordered) totalSize += positive(height.applyAsDouble(node));
        double gap = (end - start - totalSize) / (ordered.size() - 1);
        double cursor = start + positive(height.applyAsDouble(first)) + gap;
        boolean changed = false;
        for (int i = 1; i < ordered.size() - 1; i++) {
            EditorNode node = ordered.get(i);
            double next = EditorGrid.snap(cursor);
            if (Double.compare(node.y, next) != 0) {
                node.y = next;
                changed = true;
            }
            cursor += positive(height.applyAsDouble(node)) + gap;
        }
        return changed;
    }

    public static int lockedCount(List<EditorNode> nodes) {
        if (nodes == null) return 0;
        int count = 0;
        for (EditorNode node : nodes) if (node != null && node.locked) count++;
        return count;
    }

    private static void requireDimensions(ToDoubleFunction<EditorNode> width, ToDoubleFunction<EditorNode> height) {
        if (width == null || height == null) throw new IllegalArgumentException("Node width/height functions are required");
    }

    private static double positive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException("Node dimensions must be finite and positive");
        return value;
    }
}
