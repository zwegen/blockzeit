package de.blockzeit.core;

import java.util.Locale;

/** Optional provider attribution, always bound to one exact block, never to a live tip. */
public final class Pool {
    public static final String UNKNOWN = "Unknown pool";
    public static final int MAX_NAME = 48;
    public static final long RETRY_SECONDS = 15 * 60;
    public final int height;
    public final String hash, name;
    private Pool(int height, String hash, String name) {
        this.height = height; this.hash = hash; this.name = name;
    }
    public static String normalize(Object value) {
        if (!(value instanceof String)) return null;
        String raw = (String) value;
        if (raw.length() > 256) return null;
        StringBuilder out = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.length() > 0;
                continue;
            }
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE) return null;
            if (space) { out.append(' '); count++; space = false; }
            out.appendCodePoint(cp);
            if (++count > MAX_NAME) return null;
        }
        String name = out.toString();
        switch (name.toLowerCase(Locale.ROOT)) {
            case "": case "unknown": case "unavailable": case "n/a": case "?": case "—": return null;
            default: return name;
        }
    }
    public static Pool of(int height, String hash, Object value) {
        String name = normalize(value);
        return height < 0 || !Block.isHash(hash) || name == null ? null : new Pool(height, hash, name);
    }
    public boolean matches(Block block) {
        return block != null && height == block.height && hash.equals(block.hash);
    }
    public static String text(Pool pool, Block block) {
        return pool != null && pool.matches(block) ? pool.name : UNKNOWN;
    }
    public static boolean due(long now, Block block, String attemptedHash, int attemptedHeight,
                              long attemptedAt, long retryAfter) {
        if (block == null || now < retryAfter) return false;
        return !block.hash.equals(attemptedHash) || block.height != attemptedHeight
                || attemptedAt <= 0 || attemptedAt > now || now - attemptedAt >= RETRY_SECONDS;
    }
}
