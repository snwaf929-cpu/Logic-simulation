package com.foreverspark.logicsim.editor.model;

import java.util.List;

/** Immutable result shown before a BOARD template instance is replaced. */
public record BoardTemplateReplacementPreview(
        int instanceId,
        String oldTemplateName,
        String newTemplateName,
        List<SocketMapping> mappings,
        List<String> warnings,
        List<String> errors,
        int externalConnections
) {
    public BoardTemplateReplacementPreview {
        oldTemplateName = oldTemplateName == null ? "" : oldTemplateName;
        newTemplateName = newTemplateName == null ? "" : newTemplateName;
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        externalConnections = Math.max(0, externalConnections);
    }

    public boolean compatible() {
        return errors.isEmpty();
    }

    public enum MatchKind {
        INTERFACE_ID,
        NAME_DIRECTION_WIDTH
    }

    public record SocketMapping(
            BoardSocketSpec oldSocket,
            BoardSocketSpec newSocket,
            MatchKind matchKind,
            int externalConnections
    ) {
        public SocketMapping {
            externalConnections = Math.max(0, externalConnections);
        }
    }
}
