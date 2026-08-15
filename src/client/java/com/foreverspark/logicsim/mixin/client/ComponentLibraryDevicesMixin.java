package com.foreverspark.logicsim.mixin.client;

import com.foreverspark.logicsim.client.screen.CircuitCanvasWidget;
import com.foreverspark.logicsim.client.screen.ComponentLibraryWidget;
import com.foreverspark.logicsim.client.screen.v2.ExternalDeviceLibraryAccess;
import com.foreverspark.logicsim.client.screen.v2.ExternalDevicePlacementAccess;
import com.foreverspark.logicsim.editor.model.EditorNode;
import com.foreverspark.logicsim.editor.model.ExternalDeviceDescriptor;
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

/** V2.1A sidebar extension: connected physical endpoints are discoverable, but never auto-placed. */
@Mixin(ComponentLibraryWidget.class)
public abstract class ComponentLibraryDevicesMixin implements ExternalDeviceLibraryAccess {
    @Shadow private CircuitCanvasWidget canvas;
    @Shadow private Consumer<String> status;
    @Shadow private String searchQuery;

    @Unique private List<ExternalDeviceDescriptor> logic$availableDevices = List.of();
    @Unique private final List<DeviceRow> logic$deviceRows = new ArrayList<>();
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
        if (!enabled) logic$deviceRows.clear();
    }

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
    private void logic$clearDeviceRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        logic$deviceRows.clear();
    }

    @Inject(method = "drawNormalLibrary", at = @At("RETURN"), cancellable = true)
    private void logic$appendDeviceSection(
            GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!logic$deviceLibraryEnabled) return;
        int nextY = cir.getReturnValue() + 5;
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
    private void logic$appendDeviceSearchResults(
            GuiGraphicsExtractor graphics, int y, int contentTop, int footerTop,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!logic$deviceLibraryEnabled || searchQuery == null || searchQuery.isBlank()) return;
        String query = searchQuery.toLowerCase(Locale.ROOT);
        List<ExternalDeviceDescriptor> matches = logic$availableDevices.stream()
                .filter(device -> device.type().label().toLowerCase(Locale.ROOT).contains(query)
                        || device.deviceId().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        if (matches.isEmpty()) return;
        int nextY = cir.getReturnValue() + 4;
        nextY = logic$drawSection(graphics, "DEVICES", nextY, contentTop, footerTop);
        for (ExternalDeviceDescriptor device : matches) {
            nextY = logic$drawDevice(graphics, device, nextY, contentTop, footerTop);
        }
        cir.setReturnValue(nextY);
    }

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void logic$handleDeviceClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (!logic$deviceLibraryEnabled || event.button() != 0) return;
        DeviceRow hit = null;
        for (int i = logic$deviceRows.size() - 1; i >= 0; i--) {
            DeviceRow row = logic$deviceRows.get(i);
            if (event.x() >= row.left && event.x() < row.right && event.y() >= row.top && event.y() < row.bottom) {
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

        boolean armed = ((ExternalDevicePlacementAccess) canvas).logic$beginExternalDevicePlacement(hit.device);
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
    private record DeviceRow(ExternalDeviceDescriptor device, int left, int top, int right, int bottom) {}
}
