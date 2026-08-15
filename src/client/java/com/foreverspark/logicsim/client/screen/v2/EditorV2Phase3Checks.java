package com.foreverspark.logicsim.client.screen.v2;

import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import com.foreverspark.logicsim.editor.model.WireLayer;
import com.foreverspark.logicsim.editor.runtime.CircuitCompiler;
import com.foreverspark.logicsim.editor.runtime.CompiledCircuit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Dependency-light PCB routing regression checks for Logic Editor V2 Phase 3. */
public final class EditorV2Phase3Checks {
    private static final List<String> HISTORY_BRIDGE_MIXINS = List.of(
            "com.foreverspark.logicsim.mixin.client.CircuitCanvasPcbLayerMixin",
            "com.foreverspark.logicsim.mixin.client.CircuitCanvasPhase2ConfigMixin",
            "com.foreverspark.logicsim.mixin.client.CircuitCanvasWiringV2Mixin",
            "com.foreverspark.logicsim.mixin.client.CircuitCanvasDuplicateMixin",
            "com.foreverspark.logicsim.mixin.client.CircuitCanvasWireSelectionMixin"
    );

    private EditorV2Phase3Checks() {}

    public static void run() {
        layerTransitionChecks();
        viaNormalizationChecks();
        snapshotChecks();
        compilerIsolationChecks();
        wireMarqueeGeometryChecks();
        mixinHistorySignatureChecks();
    }

    private static void layerTransitionChecks() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        wire.setRoutePoints(List.of(
                new RoutePoint(24, 0),
                new RoutePoint(24, 24),
                new RoutePoint(48, 24)
        ));
        check(wire.layer() == WireLayer.FRONT, "new traces default to FRONT copper");
        wire.setViaRouteIndices(List.of(0, 2));
        check(wire.segmentLayer(0) == WireLayer.FRONT, "source segment remains on base copper before first via");
        check(wire.segmentLayer(1) == WireLayer.BACK, "first via moves following segment to BACK copper");
        check(wire.segmentLayer(2) == WireLayer.BACK, "BACK copper persists until another via");
        check(wire.segmentLayer(3) == WireLayer.FRONT, "second via returns following segment to FRONT copper");

        wire.setLayer(WireLayer.BACK);
        check(wire.segmentLayer(0) == WireLayer.BACK, "base layer can start on BACK copper");
        check(wire.segmentLayer(1) == WireLayer.FRONT, "via alternation is relative to the selected base layer");
    }

    private static void viaNormalizationChecks() {
        WireConnection wire = new WireConnection(1, 0, 2, 0);
        wire.setRoutePoints(List.of(new RoutePoint(12, 0), new RoutePoint(24, 0), new RoutePoint(36, 0)));
        wire.setViaRouteIndices(List.of(2, 0, 2, -1, 99, 1));
        check(wire.viaRouteIndices().equals(List.of(0, 1, 2)), "via indices normalize to sorted unique valid route corners");
        boolean added = wire.toggleViaAtRouteIndex(1);
        check(!added && !wire.hasViaAtRouteIndex(1), "toggling an existing via removes it");
        added = wire.toggleViaAtRouteIndex(1);
        check(added && wire.hasViaAtRouteIndex(1), "toggling an empty route corner adds a via");

        wire.setRoutePoints(List.of(new RoutePoint(12, 0)));
        check(wire.viaRouteIndices().equals(List.of(0)), "route changes discard vias that no longer reference a valid corner");
        wire.clearRoutePoints();
        check(wire.viaRouteIndices().isEmpty(), "clearing a route also clears its vias");
    }

    private static void snapshotChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        EditorNode output = document.addNode(NodeKind.OUTPUT, 96, 0);
        document.connect(input.id, 0, output.id, 0);
        WireConnection wire = document.wires.getFirst();
        wire.setRoutePoints(List.of(new RoutePoint(36, 0), new RoutePoint(60, 0)));
        wire.setLayer(WireLayer.BACK);
        wire.setViaRouteIndices(List.of(0));

        CircuitDocument copy = EditorDocumentSnapshot.copy(document);
        check(EditorDocumentSnapshot.same(document, copy), "undo snapshot preserves PCB copper side and vias");
        copy.wires.getFirst().setLayer(WireLayer.FRONT);
        copy.wires.getFirst().toggleViaAtRouteIndex(0);
        check(document.wires.getFirst().layer() == WireLayer.BACK, "PCB layer metadata is deep copied");
        check(document.wires.getFirst().hasViaAtRouteIndex(0), "PCB via metadata is deep copied");
    }

    private static void compilerIsolationChecks() {
        CircuitDocument document = new CircuitDocument();
        EditorNode input = document.addNode(NodeKind.INPUT, 0, 0);
        input.width = 8;
        EditorNode output = document.addNode(NodeKind.OUTPUT, 120, 0);
        output.width = 8;
        document.connect(input.id, 0, output.id, 0);
        WireConnection wire = document.wires.getFirst();
        wire.setRoutePoints(List.of(new RoutePoint(42, 0), new RoutePoint(78, 0)));
        wire.setLayer(WireLayer.BACK);
        wire.setViaRouteIndices(List.of(0));

        CompiledCircuit compiled = CircuitCompiler.compile(document, name -> null);
        compiled.driveInputUnsigned(input.id, 0xA5L);
        check(compiled.inputUnsigned(output.id, 0) == 0xA5L,
                "PCB layers and vias remain presentation-only and do not alter electrical simulation");
    }

    private static void wireMarqueeGeometryChecks() {
        check(EditorWireGeometry.segmentIntersectsRect(0, 10, 100, 10, 40, 60, 0, 20),
                "wire marquee selects a trace crossing the box even when neither endpoint is contained");
        check(EditorWireGeometry.segmentIntersectsRect(50, -20, 50, 40, 40, 60, 0, 20),
                "wire marquee selects a vertical trace crossing the box");
        check(EditorWireGeometry.segmentIntersectsRect(0, 0, 40, 0, 40, 60, 0, 20),
                "wire marquee includes a trace touching the selection edge");
        check(!EditorWireGeometry.segmentIntersectsRect(0, 30, 100, 30, 40, 60, 0, 20),
                "wire marquee rejects traces outside the box");
    }

    /**
     * Multiple mixins target CircuitCanvasWidget. A private @Unique helper must not reuse the
     * signature of a public EditorHistoryAccess method supplied by CircuitCanvasEditorV2Mixin.
     * Mixin can otherwise see the private method first and fail while conforming/upgrading the
     * later public interface implementation during real Minecraft class transformation.
     */
    private static void mixinHistorySignatureChecks() {
        ClassLoader loader = EditorV2Phase3Checks.class.getClassLoader();
        for (String mixinName : HISTORY_BRIDGE_MIXINS) {
            try {
                Class<?> mixin = Class.forName(mixinName, false, loader);
                for (Method helper : mixin.getDeclaredMethods()) {
                    if (!Modifier.isPrivate(helper.getModifiers())) continue;
                    for (Method api : EditorHistoryAccess.class.getDeclaredMethods()) {
                        boolean collision = helper.getName().equals(api.getName())
                                && Arrays.equals(helper.getParameterTypes(), api.getParameterTypes());
                        check(!collision, mixin.getSimpleName()
                                + " private helper collides with EditorHistoryAccess method: " + helper.getName());
                    }
                }
            } catch (ClassNotFoundException exception) {
                throw new AssertionError("History bridge mixin missing from client self-test classpath: " + mixinName, exception);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
