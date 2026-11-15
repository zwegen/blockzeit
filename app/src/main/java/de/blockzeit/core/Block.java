package de.blockzeit.core;

public final class Block {
    public final int height;
    public final long timestamp;
    public final String hash, previous;
    public Block(int height, long timestamp, String hash, String previous) {
        if (height < 0 || timestamp < 0 || timestamp > 0xffff_ffffL || !isHash(hash)
                || (height > 0 && !isHash(previous))) throw new IllegalArgumentException("Invalid block");
        this.height = height; this.timestamp = timestamp; this.hash = hash;
        this.previous = previous;
    }
    public static boolean isHash(String text) {
        if (text == null || text.length() != 64) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
    public static long parseTimestamp(String text) {
        if (text == null || !text.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Timestamp");
        long value = Long.parseLong(text);
        if (value > 0xffff_ffffL) throw new IllegalArgumentException("Timestamp range");
        return value;
    }
    public static int parseHeight(String text) {
        if (text == null || !text.trim().matches("[0-9]{1,10}")) throw new IllegalArgumentException("Height");
        return Integer.parseInt(text.trim());
    }
}
