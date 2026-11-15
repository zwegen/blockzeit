package de.blockzeit;

import java.util.Locale;

/** Strict ARGB values shared by the settings draft and the annual day ticks. */
final class YearColors {
    static final int ELAPSED = 0xffffffff, REMAINING = 0xffc2c2c2;
    private YearColors() { }
    static Integer parse(String value) {
        if (value == null) return null;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;
        long color = Long.parseLong(hex, 16);
        return (int) (hex.length() == 6 ? color | 0xff000000L : color);
    }
    static String hex(int color) { return String.format(Locale.ROOT, "#%08X", color); }
}
