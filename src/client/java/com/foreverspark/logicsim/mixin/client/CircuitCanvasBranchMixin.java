package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.chip.ClientChipLibrary;
import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.editor.model.CircuitDocument;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.NodeKind;
import com.foreverspark.logicsim.editor.model.NodePorts;
import com.foreverspark.logicsim.editor.model.PortSpec;
import com.foreverspark.logicsim.editor.model.RoutePoint;
import com.foreverspark.logicsim.editor.model.WireConnection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PCB-style wire taps. Logical fan-out remains ordinary multiple WireConnections from one
 * source port. The tap coordinate is presentation metadata only and never adds hidden logic.
 */
@Mixin(CircuitCanvasWidget.class)
public abstract class CircuitCanvasBranchMixin {
    @Unique private static final double LOGIC_BRANCH_GRID = 6.0;
    @Unique private static final double LOGIC_BRANCH_HIT_RADIUS = 10.0;

    @Shadow @Final private ClientChipLibrary chips;
    @Shadow @Final private Consumer<String> status;
    @Shadow private CircuitDocument document;
    @Shadow private WireConnection selectedWire;
    @Shadow private boolean wireEditMode;

    @Unique private LogicPendingBranch logic$pendingBranch;

    @Shadow
    private WireConnection wireAt(double mouseX, double mouseY) { throw new AssertionError(); }
    @Shadow
    private void recompile() { throw new AssertionError(); }
    @Shadow
    private int screenX(double worldX) { throw new AssertionError(); }
    @Shadow
    private int screenY(double worldY) { throw new AssertionError(); }
    @Shadow
    private double worldX(double screenX) { throw new AssertionError(); }
    @Shadow
    private double worldY(double screenY) { throw new AssertionError(); }
    @Shadow
    private double nodeWidth(EditorNode node) { throw new AssertionError(); }
    @Shadow
    private double nodeHeight(EditorNode node) { throw new AssertionError(); }
    @Shadow
    private double portStep(EditorNode node) { throw new AssertionError(); }
    @Shadow
    private double centeredPortY(EditorNode node, int port, int count) { throw new AssertionError(); }
    @Shadow
    private List<PortSpec> safeInputs(EditorNode node) { throw new AssertionError(); }
    @Shadow
    private List<PortSpec> safeOutputs(EditorNode node) { throw new AssertionError(); }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$wireBranchClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (logic$pendingBranch != null) {
            if (event.button() == 1) {
                logic$pendingBranch = null;
                status.accept("Wire branch cancelled");
                ci.cancel();
                return;
            }
            if (event.button() == 0) {
                LogicInputHit target = logic$inputAt(event.x(), event.y());
                if (target != null) {
                    logic$finishBranch(target);
                    ci.cancel();
                    return;
                }
            }
        }

        if (event.button() != 0 || !doubleClick || wireEditMode) return;
        WireConnection wire = wireAt(event.x(), event.y());
        if (wire == null) return;

        LogicTap tap = logic$closestTap(wire, event.x(), event.y());
        logic$pendingBranch = new LogicPendingBranch(wire, tap, logic$wireWidth(wire));
        selectedWire = wire;
        status.accept("BRANCH " + logic$pendingBranch.width + "-bit net — click a compatible input. Right-click cancels.");
        ci.cancel();
    }

    @Inject(method = "extractWidgetRenderState", at = @At("TAIL"))
    private void logic$renderBranches(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        for (WireConnection wire : document.wires) {
            RoutePoint tap = wire.branchStart();
            if (tap == null) continue;
            int x = screenX(tap.x());
            int y = screenY(tap.y());
            int color = logic$wireColor(wire);
            graphics.fill(x - 3, y - 3, x + 4, y + 4, color);
            graphics.outline(x - 4, y - 4, 9, 9, 0xFF090C10);
        }

        if (logic$pendingBranch == null) return;
        LogicInputHit target = logic$inputAt(mouseX, mouseY);
        int color = 0xFF6CA9FF;
        int endX = mouseX;
        int endY = mouseY;
        if (target != null) {
            color = target.spec.width() == logic$pendingBranch.width ? 0xFF55D96B : 0xFFE05252;
            LogicPoint point = logic$inputPoint(target.node, target.port);
            endX = screenX(point.x);
            endY = screenY(point.y);
        }

        int startX = screenX(logic$pendingBranch.tap.x);
        int startY = screenY(logic$pendingBranch.tap.y);
        int middleX = (startX + endX) / 2;
        logic$hLine(graphics, startX, middleX, startY, color);
        logic$vLine(graphics, middleX, startY, endY, color);
        logic$hLine(graphics, middleX, endX, endY, color);
        graphics.fill(startX - 3, startY - 3, startX + 4, startY + 4, color);
    }

    @Unique
    private void logic$finishBranch(LogicInputHit target) {
        LogicPendingBranch branch = logic$pendingBranch;
        if (branch == null) return;
        if (target.spec.width() != branch.width) {
            status.accept("WIDTH MISMATCH: branch is " + branch.width + "-bit, target is " + target.spec.width() + "-bit");
            return;
        }
        if (branch.parent.targetNodeId() == target.node.id && branch.parent.targetPort() == target.port) {
            status.accept("That input is already connected to this net");
            return;
        }

        document.connect(branch.parent.sourceNodeId(), branch.parent.sourcePort(), target.node.id, target.port);
        WireConnection created = document.wires.getLast();
        created.setBranchStart(new RoutePoint(branch.tap.x, branch.tap.y));
        created.setRoutePoints(logic$routePrefix(branch.parent, branch.tap));
        selectedWire = created;
        logic$pendingBranch = null;
        recompile();
        status.accept("Created " + branch.width + "-bit shared wire branch — every tap carries the same source value");
    }

    @Unique
    private LogicInputHit logic$inputAt(double mouseX, double mouseY) {
        for (int n = document.nodes.size() - 1; n >= 0; n--) {
            EditorNode node = document.nodes.get(n);
            List<PortSpec> ports = safeInputs(node);
            for (int port = 0; port < ports.size(); port++) {
                LogicPoint point = logic$inputPoint(node, port);
                double dx = mouseX - screenX(point.x);
                double dy = mouseY - screenY(point.y);
                if (dx * dx + dy * dy <= LOGIC_BRANCH_HIT_RADIUS * LOGIC_BRANCH_HIT_RADIUS) {
                    return new LogicInputHit(node, port, ports.get(port));
                }
            }
        }
        return null;
    }

    @Unique
    private LogicPoint logic$inputPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case CUSTOM_CHIP -> centeredPortY(node, port, safeInputs(node).size());
            case OUTPUT, PROBE, BUS, SPLITTER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x), logic$snap(y));
    }

    @Unique
    private LogicPoint logic$outputPoint(EditorNode node, int port) {
        double y = switch (node.kind) {
            case CUSTOM_CHIP -> centeredPortY(node, port, safeOutputs(node).size());
            case INPUT, NAND, CONSTANT, BUS, MERGER -> node.y + nodeHeight(node) * 0.5;
            default -> node.y + 30.0 + port * portStep(node);
        };
        return new LogicPoint(logic$snap(node.x + nodeWidth(node)), logic$snap(y));
    }

    @Unique
    private List<LogicSegment> logic$segments(WireConnection wire) {
        EditorNode source = document.node(wire.sourceNodeId());
        EditorNode target = document.node(wire.targetNodeId());
        LogicPoint start = logic$outputPoint(source, wire.sourcePort());
        LogicPoint end = logic$inputPoint(target, wire.targetPort());

        List<LogicPoint> direct = new ArrayList<>();
        direct.add(start);
        for (RoutePoint point : wire.routePoints()) direct.add(new LogicPoint(point.x(), point.y()));
        direct.add(end);

        List<LogicSegment> segments = new ArrayList<>();
        if (wire.routePoints().isEmpty() && Math.abs(start.x - end.x) > 0.001 && Math.abs(start.y - end.y) > 0.001) {
            double middleX = logic$snap((start.x + end.x) * 0.5);
            LogicPoint a = new LogicPoint(middleX, start.y);
            LogicPoint b = new LogicPoint(middleX, end.y);
            logic$appendSegment(segments, start, a);
            logic$appendSegment(segments, a, b);
            logic$appendSegment(segments, b, end);
            return segments;
        }

        for (int i = 0; i < direct.size() - 1; i++) {
            LogicPoint a = direct.get(i);
            LogicPoint b = direct.get(i + 1);
            if (Math.abs(a.x - b.x) < 0.001 || Math.abs(a.y - b.y) < 0.001) {
                logic$appendSegment(segments, a, b);
            } else {
                LogicPoint corner = new LogicPoint(b.x, a.y);
                logic$appendSegment(segments, a, corner);
                logic$appendSegment(segments, corner, b);
            }
        }
        return segments;
    }

    @Unique
    private LogicTap logic$closestTap(WireConnection wire, double mouseScreenX, double mouseScreenY) {
        double wx = worldX(mouseScreenX);
        double wy = worldY(mouseScreenY);
        List<LogicSegment> segments = logic$segments(wire);
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestX = wx;
        double bestY = wy;
        int bestIndex = 0;

        for (int i = 0; i < segments.size(); i++) {
            LogicSegment segment = segments.get(i);
            double px;
            double py;
            if (Math.abs(segment.a.y - segment.b.y) < 0.001) {
                px = logic$clamp(wx, Math.min(segment.a.x, segment.b.x), Math.max(segment.a.x, segment.b.x));
                py = segment.a.y;
            } else {
                px = segment.a.x;
                py = logic$clamp(wy, Math.min(segment.a.y, segment.b.y), Math.max(segment.a.y, segment.b.y));
            }
            double distance = Math.hypot(wx - px, wy - py);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestX = px;
                bestY = py;
                bestIndex = i;
            }
        }
        return new LogicTap(logic$snap(bestX), logic$snap(bestY), bestIndex);
    }

    @Unique
    private List<RoutePoint> logic$routePrefix(WireConnection parent, LogicTap tap) {
        List<LogicSegment> segments = logic$segments(parent);
        List<RoutePoint> route = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            LogicSegment segment = segments.get(i);
            if (i < tap.segmentIndex) {
                logic$appendRoutePoint(route, segment.b.x, segment.b.y);
            } else if (i == tap.segmentIndex) {
                logic$appendRoutePoint(route, tap.x, tap.y);
                break;
            }
        }
        return route;
    }

    @Unique
    private int logic$wireWidth(WireConnection wire) {
        EditorNode source = document.node(wire.sourceNodeId());
        List<PortSpec> outputs = NodePorts.outputs(source, chips);
        return outputs.get(wire.sourcePort()).width();
    }

    @Unique
    private int logic$wireColor(WireConnection wire) {
        int width = logic$wireWidth(wire);
        return width > 1 ? 0xFF8DB7FF : 0xFF79C4FF;
    }

    @Unique
    private static void logic$appendSegment(List<LogicSegment> segments, LogicPoint a, LogicPoint b) {
        if (Math.hypot(b.x - a.x, b.y - a.y) > 0.001) segments.add(new LogicSegment(a, b));
    }

    @Unique
    private static void logic$appendRoutePoint(List<RoutePoint> route, double x, double y) {
        double sx = logic$snap(x);
        double sy = logic$snap(y);
        if (!route.isEmpty()) {
            RoutePoint last = route.getLast();
            if (Math.abs(last.x() - sx) < 0.001 && Math.abs(last.y() - sy) < 0.001) return;
        }
        route.add(new RoutePoint(sx, sy));
    }

    @Unique
    private static double logic$snap(double value) {
        return Math.round(value / LOGIC_BRANCH_GRID) * LOGIC_BRANCH_GRID;
    }

    @Unique
    private static double logic$clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Unique
    private static void logic$hLine(GuiGraphicsExtractor graphics, int x1, int x2, int y, int color) {
        graphics.fill(Math.min(x1, x2), y - 1, Math.max(x1, x2) + 1, y + 1, color);
    }

    @Unique
    private static void logic$vLine(GuiGraphicsExtractor graphics, int x, int y1, int y2, int color) {
        graphics.fill(x - 1, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, color);
    }

    @Unique private record LogicPendingBranch(WireConnection parent, LogicTap tap, int width) {}
    @Unique private record LogicTap(double x, double y, int segmentIndex) {}
    @Unique private record LogicPoint(double x, double y) {}
    @Unique private record LogicSegment(LogicPoint a, LogicPoint b) {}
    @Unique private record LogicInputHit(EditorNode node, int port, PortSpec spec) {}
}
