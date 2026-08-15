package com.foreverspark.logicsim.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

/** Bridges CircuitCanvasWidget's existing Back action to editor-level board/chip workspace history. */
public final class EditorWorkspaceRuntime {
    private static final Map<CircuitCanvasWidget, Handler> HANDLERS = new WeakHashMap<>();

    private EditorWorkspaceRuntime() {}

    public static synchronized void register(CircuitCanvasWidget canvas, Handler handler) {
        if (canvas == null) return;
        if (handler == null) HANDLERS.remove(canvas);
        else HANDLERS.put(canvas, handler);
    }

    public static synchronized void unregister(CircuitCanvasWidget canvas) {
        if (canvas != null) HANDLERS.remove(canvas);
    }

    public static synchronized boolean canGoBack(CircuitCanvasWidget canvas) {
        Handler handler = HANDLERS.get(canvas);
        return handler != null && handler.canGoBack();
    }

    public static synchronized boolean goBack(CircuitCanvasWidget canvas) {
        Handler handler = HANDLERS.get(canvas);
        return handler != null && handler.goBack();
    }

    public interface Handler {
        boolean canGoBack();
        boolean goBack();
    }
}
