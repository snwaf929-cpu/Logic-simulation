package com.foreverspark.logicsim.block;

import com.foreverspark.logicsim.display.DisplayCommandCodec;
import com.foreverspark.logicsim.display.DisplayFramebuffer;
import com.foreverspark.logicsim.interconnect.CableKind;
import com.foreverspark.logicsim.interconnect.CableRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class DisplayBlockEntity extends BlockEntity {
    public static final int MAX_WIDTH = 64;
    public static final int MAX_HEIGHT = 64;
    public static final int DEFAULT_PIXEL_WIDTH = 32;
    public static final int DISPLAY_BUS_WIDTH = 64;
    public static final int OP_NOP = DisplayCommandCodec.OP_NOP;
    public static final int OP_PIXEL = DisplayCommandCodec.OP_PIXEL;
    public static final int OP_CLEAR = DisplayCommandCodec.OP_CLEAR;

    /** v3 stores the complete 64x64 RGB565 framebuffer as one compact packed int array. */
    private static final int FRAMEBUFFER_VERSION = 3;
    private static final int[] PIXEL_WIDTHS = {1, 2, 4, 8, 16, 32, 64};
    private static final int MAX_WALL_BLOCKS = 4096;

    /**
     * The backing store is ALWAYS 64x64, independent of the selected visible density.
     * A low-resolution logical pixel occupies a rectangle inside this backing store. That means changing
     * 1 -> 2 -> 4 ... never destroys the image; higher resolutions simply reveal more of the same memory.
     */
    private final DisplayFramebuffer framebuffer = new DisplayFramebuffer(MAX_WIDTH, MAX_HEIGHT);
    private int pixelWidth = DEFAULT_PIXEL_WIDTH;
    private boolean syncPending;
    /** Send one authoritative framebuffer packet after a server-side block entity/chunk load. */
    private boolean initialClientSyncSent;
    private long lastWallCommand = Long.MIN_VALUE;
    /** Last raw DATA64 observed by this physical tile; used to avoid rescanning an unchanged wall every tick. */
    private long lastCableValue = Long.MIN_VALUE;
    private boolean hasReceivedData;
    private long lastReceivedData;
    private String lastActionStatus = "No DRAW/CLEAR command accepted yet";

    /** Redstone is wall-wide: one directly powered tile powers every same-facing connected display tile. */
    private boolean powerInitialized;
    private boolean localRedstonePowered;
    private boolean wallPowered;

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISPLAY, pos, state);
    }

    public DisplayFramebuffer framebuffer() { return framebuffer; }
    public int pixelWidth() { return pixelWidth; }
    public int pixelHeight() { return pixelHeightFor(pixelWidth); }
    public boolean wallPowered() { return wallPowered; }
    public static int pixelHeightFor(int width) { return width; }

    public static int nextPixelWidth(int current) {
        for (int index = 0; index < PIXEL_WIDTHS.length; index++) {
            if (PIXEL_WIDTHS[index] == current) return PIXEL_WIDTHS[(index + 1) % PIXEL_WIDTHS.length];
        }
        return DEFAULT_PIXEL_WIDTH;
    }

    public static long pixelCommand(int x, int y, int rgb565, int sequence) {
        return DisplayCommandCodec.pixel(x, y, rgb565, sequence);
    }

    public static long clearCommand(int sequence) {
        return DisplayCommandCodec.clear(sequence);
    }

    /** Changes only the visible logical density. The 64x64 backing framebuffer is deliberately preserved. */
    public void setPixelWidth(int width) {
        int normalized = normalizePixelWidth(width);
        if (normalized == pixelWidth) return;
        pixelWidth = normalized;
        framebuffer.markAllDirty();
        lastActionStatus = "Resolution changed to " + normalized + "x" + normalized + "; framebuffer preserved";
        setChanged();
    }

    public static int setWallPixelWidth(Level level, BlockPos start, BlockState startState, int width) {
        DisplayWall wall = collectWall(level, start, startState);
        if (wall == null) return 0;
        int changed = 0;
        for (BlockPos pos : wall.blocks()) {
            if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
                display.setPixelWidth(width);
                changed++;
            }
        }
        return changed;
    }

    /** Human-facing geometry/status for one connected, same-facing display wall. */
    public static WallInfo wallInfo(Level level, BlockPos start, BlockState startState) {
        DisplayWall wall = collectWall(level, start, startState);
        if (wall == null || wall.blocks().isEmpty()) return null;
        int density = level.getBlockEntity(start) instanceof DisplayBlockEntity display
                ? display.pixelWidth()
                : DEFAULT_PIXEL_WIDTH;
        int columns = wall.maxHorizontal() - wall.minHorizontal() + 1;
        int rows = wall.maxY() - wall.minY() + 1;
        return new WallInfo(
                wall.blocks().size(),
                columns,
                rows,
                density,
                columns * density,
                rows * density,
                DISPLAY_BUS_WIDTH
        );
    }

    /** Electrical diagnostics for the controller tile of the connected wall. */
    public static WallSignalInfo wallSignalInfo(Level level, BlockPos start, BlockState startState) {
        DisplayWall wall = collectWall(level, start, startState);
        if (wall == null || wall.blocks().isEmpty()) return null;
        BlockPos controllerPos = controllerPos(wall, start);
        if (!(level.getBlockEntity(controllerPos) instanceof DisplayBlockEntity controller)) return null;
        return new WallSignalInfo(
                controller.hasReceivedData,
                controller.lastReceivedData,
                controller.lastActionStatus,
                controller.wallPowered
        );
    }

    /** Returns the visible logical RGB565 value for a global screen coordinate, or -1 when invalid/missing. */
    public static int wallPixelRgb565(Level level, BlockPos start, BlockState startState, int globalX, int globalY) {
        DisplayWall wall = collectWall(level, start, startState);
        if (wall == null || wall.blocks().isEmpty()) return -1;
        int density = level.getBlockEntity(start) instanceof DisplayBlockEntity display
                ? display.pixelWidth()
                : DEFAULT_PIXEL_WIDTH;
        int columns = wall.maxHorizontal() - wall.minHorizontal() + 1;
        int rows = wall.maxY() - wall.minY() + 1;
        if (globalX < 0 || globalY < 0 || globalX >= columns * density || globalY >= rows * density) return -1;

        BlockPos target = targetForGlobalPixel(wall, start, density, globalX, globalY);
        if (target == null || !wall.blocks().contains(target)) return -1;
        if (!(level.getBlockEntity(target) instanceof DisplayBlockEntity display)) return -1;
        return display.logicalPixelRgb565(globalX % density, globalY % density);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DisplayBlockEntity display) {
        if (level.isClientSide()) return;

        boolean locallyPowered = level.hasNeighborSignal(pos);
        if (!display.powerInitialized || locallyPowered != display.localRedstonePowered) {
            refreshWallPower(level, pos, state);
        }

        if (!display.initialClientSyncSent) {
            display.initialClientSyncSent = true;
            display.syncPending = true;
        }

        for (Direction direction : Direction.values()) {
            if (direction == DisplayPorts.front(state)) continue;
            BlockPos cablePos = pos.relative(direction);
            BlockState cableState = level.getBlockState(cablePos);
            if (cableState.getBlock() instanceof CableBlock cable
                    && cable.cableKind() == CableKind.BUS
                    && cable.bitWidth() == DISPLAY_BUS_WIDTH) {
                display.acceptCableValue(direction, CableRuntime.value(level, cablePos));
            }
        }

        display.flushClientSync(level);
    }

    private static void refreshWallPower(Level level, BlockPos touchedTile, BlockState touchedState) {
        DisplayWall wall = collectWall(level, touchedTile, touchedState);
        if (wall == null || wall.blocks().isEmpty()) return;

        boolean powered = false;
        for (BlockPos pos : wall.blocks()) {
            if (level.hasNeighborSignal(pos)) {
                powered = true;
                break;
            }
        }

        BlockPos controllerPos = controllerPos(wall, touchedTile);
        DisplayBlockEntity controller = level.getBlockEntity(controllerPos) instanceof DisplayBlockEntity display
                ? display
                : null;
        boolean wallStateChanged = controller == null || !controller.powerInitialized || controller.wallPowered != powered;

        for (BlockPos pos : wall.blocks()) {
            if (!(level.getBlockEntity(pos) instanceof DisplayBlockEntity display)) continue;
            boolean tileWasInitialized = display.powerInitialized;
            boolean tileStateChanged = !tileWasInitialized || display.wallPowered != powered;

            display.localRedstonePowered = level.hasNeighborSignal(pos);
            display.powerInitialized = true;
            display.wallPowered = powered;

            if (tileStateChanged) {
                display.lastCableValue = Long.MIN_VALUE;
                display.lastWallCommand = Long.MIN_VALUE;
                if (!powered) {
                    display.clearScreen();
                } else {
                    display.setChanged();
                }
            }
        }

        if (controller != null && wallStateChanged) {
            controller.lastActionStatus = powered
                    ? "Display wall powered ON"
                    : "Display wall powered OFF; framebuffer cleared";
            controller.setChanged();
        }
    }

    /** One 64-bit bus touching any non-front face of any tile controls the entire connected wall. */
    public void acceptCableValue(Direction face, long value) {
        if (level == null || level.isClientSide()) return;
        if (!DisplayPorts.accepts(getBlockState(), face, CableKind.BUS, DISPLAY_BUS_WIDTH)) return;
        if (lastCableValue == value) return;
        lastCableValue = value;
        routeWallCommand(level, worldPosition, value);
    }

    private static void routeWallCommand(Level level, BlockPos touchedTile, long command) {
        BlockState touchedState = level.getBlockState(touchedTile);
        DisplayWall wall = collectWall(level, touchedTile, touchedState);
        if (wall == null || wall.blocks().isEmpty()) return;

        BlockPos controllerPos = controllerPos(wall, touchedTile);
        if (!(level.getBlockEntity(controllerPos) instanceof DisplayBlockEntity controller)) return;
        controller.hasReceivedData = true;
        controller.lastReceivedData = command;

        if (!controller.wallPowered) {
            controller.lastActionStatus = "DATA64 received but display wall is OFF (no redstone power)";
            controller.setChanged();
            return;
        }

        if (controller.lastWallCommand == command) return;
        controller.lastWallCommand = command;

        DisplayCommandCodec.Command decoded = DisplayCommandCodec.decode(command);
        if (decoded.isNop()) return;

        if (decoded.isClear()) {
            for (BlockPos pos : wall.blocks()) {
                if (level.getBlockEntity(pos) instanceof DisplayBlockEntity display) display.clearScreen();
            }
            controller.lastActionStatus = "CLEAR accepted";
            controller.setChanged();
            return;
        }

        if (!decoded.isPixel()) {
            controller.lastActionStatus = "IGNORED invalid opcode " + decoded.opcode();
            controller.setChanged();
            return;
        }

        int globalX = decoded.x();
        int globalY = decoded.y();
        int rgb565 = decoded.rgb565();
        int density = controller.pixelWidth();

        int columns = wall.maxHorizontal() - wall.minHorizontal() + 1;
        int rows = wall.maxY() - wall.minY() + 1;
        int screenWidth = columns * density;
        int screenHeight = rows * density;
        if (globalX >= screenWidth || globalY >= screenHeight) {
            controller.lastActionStatus = "DRAW rejected: (" + globalX + "," + globalY + ") outside "
                    + screenWidth + "x" + screenHeight;
            controller.setChanged();
            return;
        }

        BlockPos target = targetForGlobalPixel(wall, touchedTile, density, globalX, globalY);
        if (target == null || !wall.blocks().contains(target)) {
            controller.lastActionStatus = "DRAW rejected: target tile is missing at (" + globalX + "," + globalY + ")";
            controller.setChanged();
            return;
        }
        if (level.getBlockEntity(target) instanceof DisplayBlockEntity display) {
            display.writePixel(globalX % density, globalY % density, rgb565);
            controller.lastActionStatus = "DRAW accepted: x=" + globalX + " y=" + globalY
                    + " color=" + String.format(java.util.Locale.ROOT, "0x%04X", rgb565);
            controller.setChanged();
        }
    }

    private static BlockPos targetForGlobalPixel(DisplayWall wall, BlockPos origin, int density, int globalX, int globalY) {
        int targetHorizontal = wall.minHorizontal() + globalX / density;
        int targetY = wall.maxY() - globalY / density;
        return origin.offset(
                wall.right().getStepX() * targetHorizontal,
                targetY - origin.getY(),
                wall.right().getStepZ() * targetHorizontal
        );
    }

    private static BlockPos controllerPos(DisplayWall wall, BlockPos fallback) {
        return wall.blocks().stream()
                .min(Comparator.comparingLong(BlockPos::asLong))
                .orElse(fallback);
    }

    private static DisplayWall collectWall(Level level, BlockPos start, BlockState startState) {
        if (level == null || start == null || !(startState.getBlock() instanceof DisplayBlock)) return null;
        Direction facing = DisplayPorts.front(startState);
        Direction left = DisplayPorts.left(startState);
        Direction right = left.getOpposite();

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> blocks = new HashSet<>();
        queue.add(start.immutable());

        int minHorizontal = 0;
        int maxHorizontal = 0;
        int minY = start.getY();
        int maxY = start.getY();

        while (!queue.isEmpty() && seen.size() < MAX_WALL_BLOCKS) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DisplayBlock) || DisplayPorts.front(state) != facing) continue;

            blocks.add(pos.immutable());
            int dx = pos.getX() - start.getX();
            int dz = pos.getZ() - start.getZ();
            int horizontal = dx * right.getStepX() + dz * right.getStepZ();
            minHorizontal = Math.min(minHorizontal, horizontal);
            maxHorizontal = Math.max(maxHorizontal, horizontal);
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());

            queue.add(pos.relative(left));
            queue.add(pos.relative(right));
            queue.add(pos.relative(Direction.UP));
            queue.add(pos.relative(Direction.DOWN));
        }

        return new DisplayWall(Set.copyOf(blocks), right, minHorizontal, maxHorizontal, minY, maxY);
    }

    public void writePixel(int x, int y, int rgb565) {
        if (x < 0 || x >= pixelWidth || y < 0 || y >= pixelHeight()) return;
        int scaleX = MAX_WIDTH / pixelWidth;
        int scaleY = MAX_HEIGHT / pixelHeight();
        int minX = x * scaleX;
        int minY = y * scaleY;
        int maxX = minX + scaleX - 1;
        int maxY = minY + scaleY - 1;

        long before = framebuffer.revision();
        if (!framebuffer.fillRect(minX, minY, maxX, maxY, rgb565)) return;
        if (framebuffer.revision() != before) {
            setChanged();
        } else {
            syncPending = true;
        }
    }

    public int logicalPixelRgb565(int x, int y) {
        if (x < 0 || x >= pixelWidth || y < 0 || y >= pixelHeight()) return 0;
        int scaleX = MAX_WIDTH / pixelWidth;
        int scaleY = MAX_HEIGHT / pixelHeight();
        int minX = x * scaleX;
        int minY = y * scaleY;
        int maxX = minX + scaleX;
        int maxY = minY + scaleY;

        for (int backingY = minY; backingY < maxY; backingY++) {
            for (int backingX = minX; backingX < maxX; backingX++) {
                int value = framebuffer.pixelRgb565(backingX, backingY);
                if (value != 0) return value;
            }
        }
        return 0;
    }

    public int logicalPixelArgb(int x, int y) {
        return DisplayFramebuffer.rgb565ToArgb(logicalPixelRgb565(x, y));
    }

    public void clearScreen() {
        long before = framebuffer.revision();
        framebuffer.clear(0);
        if (framebuffer.revision() != before) {
            setChanged();
        } else {
            syncPending = true;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putInt("pixelWidth", pixelWidth);
        output.putInt("framebufferVersion", FRAMEBUFFER_VERSION);
        if (!framebuffer.isBlack()) output.putIntArray("framebuffer", framebuffer.packedRgb565());
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        pixelWidth = normalizePixelWidth(input.getIntOr("pixelWidth", DEFAULT_PIXEL_WIDTH));

        int version = input.getIntOr("framebufferVersion", 1);
        if (version >= FRAMEBUFFER_VERSION) {
            framebuffer.loadPackedRgb565(input.getIntArray("framebuffer").orElse(null));
        } else {
            framebuffer.clear(0);
            if (version >= 2) {
                // Upgrade v2 saves that stored a complete 64x64 backing framebuffer as p0...p4095 keys.
                for (int index = 0; index < MAX_WIDTH * MAX_HEIGHT; index++) {
                    int value = input.getIntOr("p" + index, 0);
                    if (value != 0) framebuffer.writePixel(index % MAX_WIDTH, index / MAX_WIDTH, value);
                }
            } else {
                // Upgrade v1 saves that stored only the active NxN logical framebuffer.
                int oldWidth = pixelWidth;
                int oldHeight = pixelHeight();
                int scaleX = MAX_WIDTH / oldWidth;
                int scaleY = MAX_HEIGHT / oldHeight;
                for (int index = 0; index < oldWidth * oldHeight; index++) {
                    int value = input.getIntOr("p" + index, 0);
                    if (value == 0) continue;
                    int x = index % oldWidth;
                    int y = index / oldWidth;
                    int minX = x * scaleX;
                    int minY = y * scaleY;
                    framebuffer.fillRect(minX, minY, minX + scaleX - 1, minY + scaleY - 1, value);
                }
            }
            framebuffer.markAllDirty();
        }

        initialClientSyncSent = false;
        lastWallCommand = Long.MIN_VALUE;
        lastCableValue = Long.MIN_VALUE;
        hasReceivedData = false;
        lastReceivedData = 0L;
        lastActionStatus = "Loaded screen; waiting for redstone power and DATA64";
        powerInitialized = false;
        localRedstonePowered = false;
        wallPowered = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        syncPending = true;
    }

    private void flushClientSync(Level level) {
        if (!syncPending) return;
        syncPending = false;
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }

    private static int normalizePixelWidth(int width) {
        for (int candidate : PIXEL_WIDTHS) if (candidate == width) return candidate;
        return DEFAULT_PIXEL_WIDTH;
    }

    public record WallInfo(int tileCount, int columns, int rows, int pixelsPerTile, int pixelWidth, int pixelHeight, int dataBusWidth) {}
    public record WallSignalInfo(boolean hasReceivedData, long lastReceivedData, String lastActionStatus, boolean powered) {}
    private record DisplayWall(Set<BlockPos> blocks, Direction right, int minHorizontal, int maxHorizontal, int minY, int maxY) {}
}
