package com.foreverspark.logicsim.client.screen.v2;

/** Width-aware unsigned value parsing/formatting for INPUT and CONSTANT editors. */
public final class NumericValueCodec {
    private NumericValueCodec() {}

    public enum Radix { HEX, DEC, BIN }

    public static long parse(String text, Radix radix, int width) {
        width = checkedWidth(width);
        String raw = text == null ? "" : text.trim().replace("_", "").replace(" ", "");
        if (raw.isEmpty()) throw new IllegalArgumentException("Enter a value");
        int base = switch (radix) { case HEX -> 16; case DEC -> 10; case BIN -> 2; };
        if (radix == Radix.HEX && (raw.startsWith("0x") || raw.startsWith("0X"))) raw = raw.substring(2);
        if (radix == Radix.BIN && (raw.startsWith("0b") || raw.startsWith("0B"))) raw = raw.substring(2);
        if (raw.isEmpty() || raw.startsWith("-")) throw new IllegalArgumentException("Value must be unsigned");
        long value;
        try {
            value = Long.parseUnsignedLong(raw, base);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + radix.name().toLowerCase() + " value");
        }
        if (width < 64 && (value >>> width) != 0L) {
            throw new IllegalArgumentException("Value does not fit in " + width + " bits");
        }
        return value;
    }

    public static String hex(long value, int width) {
        width = checkedWidth(width);
        value &= mask(width);
        int digits = Math.max(1, (width + 3) / 4);
        String raw = Long.toUnsignedString(value, 16).toUpperCase();
        if (raw.length() < digits) raw = "0".repeat(digits - raw.length()) + raw;
        return "0x" + raw;
    }

    public static String dec(long value, int width) {
        return Long.toUnsignedString(value & mask(checkedWidth(width)));
    }

    public static String bin(long value, int width) {
        width = checkedWidth(width);
        value &= mask(width);
        String raw = Long.toBinaryString(value);
        if (raw.length() < width) raw = "0".repeat(width - raw.length()) + raw;
        StringBuilder grouped = new StringBuilder(raw.length() + raw.length() / 4);
        int first = raw.length() % 4;
        int cursor = 0;
        if (first != 0) {
            grouped.append(raw, 0, first);
            cursor = first;
            if (cursor < raw.length()) grouped.append(' ');
        }
        while (cursor < raw.length()) {
            grouped.append(raw, cursor, cursor + 4);
            cursor += 4;
            if (cursor < raw.length()) grouped.append(' ');
        }
        return grouped.toString();
    }

    public static long mask(int width) {
        width = checkedWidth(width);
        return width == 64 ? -1L : (1L << width) - 1L;
    }

    private static int checkedWidth(int width) {
        if (width < 1 || width > 64) throw new IllegalArgumentException("Width must be from 1 to 64");
        return width;
    }
}
