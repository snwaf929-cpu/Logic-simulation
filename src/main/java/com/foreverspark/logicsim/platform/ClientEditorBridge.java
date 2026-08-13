package com.foreverspark.logicsim.platform;

import java.util.Objects;

/**
 * Common-side bridge that keeps client-only Minecraft classes out of the main source set.
 * Dedicated servers retain the no-op opener.
 */
public final class ClientEditorBridge {
    private static Runnable editorOpener = () -> {
    };

    private ClientEditorBridge() {
    }

    public static void installEditorOpener(Runnable opener) {
        editorOpener = Objects.requireNonNull(opener, "opener");
    }

    public static void openEditor() {
        editorOpener.run();
    }
}
