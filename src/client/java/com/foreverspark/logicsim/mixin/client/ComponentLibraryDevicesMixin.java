package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.v2.BoardTemplateCanvasAccess;
import com.foreverspark.logicsim.client.screen.v2.ExternalDeviceLibraryAccess;
import com.foreverspark.logicsim.client.screen.v2.ExternalDevicePlacementAccess;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
import com.foreverspark.logicsim.editor.model.PortDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * V2.1A BOARD sidebar extension. BOARD sockets and connected physical endpoints are explicit library items;
 * discovery never injects anything onto the schematic by itself.
 */
@Mixin(ComponentLibraryWidget.class)
public abstract class ComponentLibraryDevicesMixin implements ExternalDeviceLibraryAccess {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;
    @Shadow private String searchQuery;

    @Unique private List<ExternalDeviceDescriptor> logic$availableDevices = List.of();
    @Unique private final List<DeviceRow> logic$deviceRows = new ArrayList<>();
    @Unique private final List<IoRow> logic$ioRows = new ArrayList<>();
    @Unique private boolean logic$deviceLibraryEnabled;

    @Override
    public void logic$setAvailableDevices(List<ExternalDeviceDescriptor> devices) {
        if (devices == null || devices.isEmpty()) {
            logic$availableDevices = List.of();
            return;
        }
        logic$availableDevices = devices.stream()
                .filter(java.util.Objects::nonNull)
                .filter(device -> device.deviceId() != null && !device.deviceId().isBlank())
                .sorted(Comparator.comparing((ExternalDeviceDescriptor device) -> device.type().ordinal())
                        .thenComparing(ExternalDeviceDescriptor::deviceId))
                .toList();
    }

    @Override
    public void logic$setDeviceLibraryEnabled(boolean enabled) {
        logic$deviceLibraryEnabled = enabled;
        if (!enabled) {
            logic$deviceRows.clear();
            logic$ioRows.clear();
        }
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$clearExtraRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$deviceRows.clear();
        logic$ioRows.clear();
    }

    @Inject(method = "drawNormalLibrary", at = @At("RETURN"), cancellable = true)
    private void logic$appendBoardSections(
            GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!logic$deviceLibraryEnabled) return;
        int nextY = cir.getReturnValue() + 5;

        nextY = logic$drawSection(graphics, "BOARD I/O", nextY, contentTop, footerTop);
        nextY = logic$drawIo(graphics, "INPUT SOCKET", PortDirection.INPUT, nextY, contentTop, footerTop);
        nextY = logic$drawIo(graphics, "OUTPUT SOCKET", PortDirection.OUTPUT, nextY, contentTop, footerTop);

        nextY += 4;
        nextY = logic$drawSection(graphics, "DEVICES", nextY, contentTop, footerTop);
        if (logic$availableDevices.isEmpty()) {
            nextY = logic$drawHint(graphics, "No connected devices", nextY, contentTop, footerTop);
        } else {
            for (ExternalDeviceDescriptor device : logic$availableDevices) {
                nextY = logic$drawDevice(graphics, device, nextY, contentTop, footerTop);
            }
        }
        cir.setReturnValue(nextY);
    }

    @Inject(method = "drawSearchResults", at = @At("RETURN"), cancellable = true)
    private void logic$appendBoardSearchResults(
            GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!logic$deviceLibraryEnabled || searchQuery == null || searchQuery.isBlank()) return;
        String query = searchQuery.toLowerCase(Locale.ROOT);
        int nextY = cir.getReturnValue();

        boolean inputSocket = "input socket".contains(query) || "board input".contains(query) || "socket".equals(query);
        boolean outputSocket = "output socket".contains(query) || "board output".contains(query) || "socket".equals(query);
        if (inputSocket || outputSocket) {
            nextY += 4;
            nextY = logic$drawSection(graphics, "BOARD I/O", nextY, contentTop, footerTop);
            if (inputSocket) nextY = logic$drawIo(graphics, "INPUT SOCKET", PortDirection.INPUT, nextY, contentTop, footerTop);
            if (outputSocket) nextY = logic$drawIo(graphics, "OUTPUT SOCKET", PortDirection.OUTPUT, nextY, contentTop, footerTop);
        }

        List<ExternalDeviceDescriptor> matches = logic$availableDevices.stream()
                .filter(device -> device.type().label().toLowerCase(Locale.ROOT).contains(query)
                        || device.deviceId().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        if (!matches.isEmpty()) {
            nextY += 4;
            nextY = logic$drawSection(graphics, "DEVICES", nextY, contentTop, footerTop);
            for (ExternalDeviceDescriptor device : matches) {
                nextY = logic$drawDevice(graphics, device, nextY, contentTop, footerTop);
            }
        }
        cir.setReturnValue(nextY);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$handleBoardExtensionClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (!logic$deviceLibraryEnabled || event.button() != 0) return;

        for (int i = logic$ioRows.size() - 1; i >= 0; i--) {
            IoRow row = logic$ioRows.get(i);
            if (!row.hit(event.x(), event.y())) continue;
            canvas.cancelPlacement();
            ((BoardTemplateCanvasAccess) (Object) canvas).logic$beginSocketPlacement(row.direction);
            status.accept("Place " + row.label + " — click the BOARD; select it and press W to configure name/width/order");
            ci.cancel();
            return;
        }

        DeviceRow hit = null;
        for (int i = logic$deviceRows.size() - 1; i >= 0; i--) {
            DeviceRow row = logic$deviceRows.get(i);
            if (row.hit(event.x(), event.y())) {
                hit = row;
                break;
            }
        }
        if (hit == null) return;

        if (logic$isPlaced(hit.device.deviceId())) {
            status.accept(hit.device.type().label() + " is already placed on this BOARD");
            ci.cancel();
            return;
        }

        boolean armed = ((ExternalDevicePlacementAccess) (Object) canvas).logic$beginExternalDevicePlacement(hit.device);
        if (armed) {
            status.accept("Place " + hit.device.type().label() + " — click the BOARD canvas; right-click cancels");
        } else {
            status.accept("Could not place " + hit.device.type().label() + " — it may already be on this BOARD");
        }
        ci.cancel();
    }

    @Unique
    private int logic$drawSection(GuiGraphicsExtractor graphics, String title, int y, int clipTop, int clipBottom) {
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        if (logic$visible(y, 14, clipTop, clipBottom)) {
            graphics.text(Minecraft.getInstance().font, title, self.getX() + 7, y + 3, 0xFF737E8B, false);
        }
        return y + 14;
    }

    @Unique
    private int logic$drawHint(GuiGraphicsExtractor graphics, String text, int y, int clipTop, int clipBottom) {
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        if (logic$visible(y, 18, clipTop, clipBottom)) {
            graphics.text(Minecraft.getInstance().font, text, self.getX() + 13, y + 5, 0xFF66727F, false);
        }
        return y + 19;
    }

    @Unique
    private int logic$drawIo(
            GuiGraphicsExtractor graphics, String label, PortDirection direction,
            int y, int clipTop, int clipBottom
    ) {
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int height = 18;
        if (logic$visible(y, height, clipTop, clipBottom)) {
            int accent = direction == PortDirection.INPUT ? 0xFF4C86D9 : 0xFF7B68D9;
            graphics.fill(left, y, right, y + height - 1, 0xFF181F26);
            graphics.fill(left, y, left + 4, y + height - 1, accent);
            graphics.text(Minecraft.getInstance().font, label, left + 9, y + 5, 0xFFD7DEE8, false);
            logic$ioRows.add(new IoRow(label, direction, left, y, right, y + height));
        }
        return y + height + 1;
    }

    @Unique
    private int logic$drawDevice(
            GuiGraphicsExtractor graphics, ExternalDeviceDescriptor device,
            int y, int clipTop, int clipBottom
    ) {
        ComponentLibraryWidget self = (ComponentLibraryWidget) (Object) this;
        int left = self.getX() + 5;
        int right = self.getX() + self.getWidth() - 5;
        int height = 18;
        boolean placed = logic$isPlaced(device.deviceId());
        if (logic$visible(y, height, clipTop, clipBottom)) {
            int accent = switch (device.type()) {
                case DISPLAY -> 0xFFD28A45;
                case UIB -> 0xFF63A9D8;
                case INTERNET -> 0xFF55B96B;
                case STORAGE -> 0xFFB06CE8;
            };
            graphics.fill(left, y, right, y + height - 1, placed ? 0xFF20262B : 0xFF181F26);
            graphics.fill(left, y, left + 4, y + height - 1, accent);
            String label = device.type().label();
            if (label.length() > 19) label = label.substring(0, 18) + "…";
            graphics.text(Minecraft.getInstance().font, label, left + 9, y + 5, placed ? 0xFF8B98A5 : 0xFFD7DEE8, false);
            String badge = placed ? "PLACED" : "READY";
            int badgeX = Math.max(left + 78, right - Minecraft.getInstance().font.width(badge) - 5);
            graphics.text(Minecraft.getInstance().font, badge, badgeX, y + 5, placed ? 0xFF7A8792 : 0xFF7FCB90, false);
            logic$deviceRows.add(new DeviceRow(device, left, y, right, y + height));
        }
        return y + height + 1;
    }

    @Unique
    private boolean logic$isPlaced(String deviceId) {
        if (deviceId == null || canvas == null || canvas.document() == null) return false;
        for (EditorNode node : canvas.document().externalDeviceNodes()) {
            if (deviceId.equals(node.externalDeviceId)) return true;
        }
        return false;
    }

    @Unique
    private static boolean logic$visible(int y, int height, int top, int bottom) {
        return y + height > top && y < bottom;
    }

    @Unique
    private record DeviceRow(ExternalDeviceDescriptor device, int left, int top, int right, int bottom) {
        boolean hit(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    }

    @Unique
    private record IoRow(String label, PortDirection direction, int left, int top, int right, int bottom) {
        boolean hit(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    }
}
