package com.foreverspark.logicsim.display;

/** Single source of truth for the physical screen DATA[64] protocol. */
public final class DisplayCommandCodec {
    public static final int OP_NOP = 0;
    public static final int OP_PIXEL = 1;
    public static final int OP_CLEAR = 2;

    private DisplayCommandCodec() {}

    public static long pixel(int x, int y, int rgb565) {
        return ((long) OP_PIXEL << 48)
                | ((long) (y & 0xFFFF) << 32)
                | ((long) (x & 0xFFFF) << 16)
                | (rgb565 & 0xFFFFL);
    }

    public static long pixel(int x, int y, int rgb565, int sequence) {
        return ((long) (sequence & 0xFF) << 56) | pixel(x, y, rgb565);
    }

    public static long clear() {
        return (long) OP_CLEAR << 48;
    }

    public static long clear(int sequence) {
        return ((long) (sequence & 0xFF) << 56) | clear();
    }

    public static Command decode(long data) {
        return new Command(
                (int) ((data >>> 48) & 0xFFL),
                (int) ((data >>> 16) & 0xFFFFL),
                (int) ((data >>> 32) & 0xFFFFL),
                (int) (data & 0xFFFFL),
                (int) ((data >>> 56) & 0xFFL),
                data
        );
    }

    public static String hex(long value) {
        return String.format(java.util.Locale.ROOT, "0x%016X", value);
    }

    public record Command(int opcode, int x, int y, int rgb565, int sequence, long raw) {
        public boolean isNop() { return opcode == OP_NOP; }
        public boolean isPixel() { return opcode == OP_PIXEL; }
        public boolean isClear() { return opcode == OP_CLEAR; }
    }
}
