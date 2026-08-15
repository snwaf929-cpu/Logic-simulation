package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps existing compiler diagnostics back onto authored schematic objects for Phase 6 highlighting. */
public final class EditorErrorLocator {
    private static final Pattern NODE_ID = Pattern.compile("(?i)\\bnode\\s+(\\d+)\\b");
    private static final Pattern NET_NAME = Pattern.compile("(?i)NET_LABEL\\s+(.+?)\\s+(?:width mismatch|has multiple drivers)");
    private static final Pattern CHIP_NAME = Pattern.compile("(?i)(?:Missing custom chip|Recursive custom chip reference):\\s*(.+)$");
    private static final Pattern STRUCTURAL_LOOP = Pattern.compile("(?i)Structural wiring loop detected at (.+?) output \\d+");

    private EditorErrorLocator() {}

    public static Set<Integer> locate(CircuitDocument document, String diagnostic) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        if (document == null || diagnostic == null || diagnostic.isBlank()) return result;

        Matcher nodeMatcher = NODE_ID.matcher(diagnostic);
        while (nodeMatcher.find()) {
            try {
                int id = Integer.parseInt(nodeMatcher.group(1));
                document.node(id);
                result.add(id);
            } catch (RuntimeException ignored) {
                // Diagnostic can reference a missing node; there is then no live object to highlight.
            }
        }

        Matcher netMatcher = NET_NAME.matcher(diagnostic);
        if (netMatcher.find()) {
            String net = netMatcher.group(1).trim();
            for (EditorNode node : document.nodes) {
                if (node.kind == NodeKind.NET_LABEL && safe(node.label).trim().equalsIgnoreCase(net)) result.add(node.id);
            }
        }

        Matcher chipMatcher = CHIP_NAME.matcher(diagnostic);
        if (chipMatcher.find()) {
            String chip = chipMatcher.group(1).trim();
            for (EditorNode node : document.nodes) {
                if (node.kind == NodeKind.CUSTOM_CHIP && safe(node.chipName).trim().equalsIgnoreCase(chip)) result.add(node.id);
            }
        }

        Matcher loopMatcher = STRUCTURAL_LOOP.matcher(diagnostic);
        if (loopMatcher.find()) {
            String displayName = loopMatcher.group(1).trim();
            for (EditorNode node : document.nodes) if (node.displayName().equalsIgnoreCase(displayName)) result.add(node.id);
        }
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
