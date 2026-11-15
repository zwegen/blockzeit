package de.blockzeit.core;

import java.util.List;

/** Descending, hash-linked header history. Never stop at the first pre-midnight timestamp. */
public final class History {
    public static final int MAX_HEADERS = 640;
    public static final long BOUNDARY_MARGIN = 24 * 3600;
    private History() { }
    public static void append(List<Block> chain, Block next) {
        if (chain.size() >= MAX_HEADERS) throw new IllegalArgumentException("History limit");
        if (!chain.isEmpty()) {
            Block prior = chain.get(chain.size() - 1);
            if (next.height != prior.height - 1 || !prior.previous.equals(next.hash)) {
                throw new IllegalArgumentException("Broken/reorganized chain");
            }
        }
        chain.add(next);
    }
    /** Conservative operational boundary: 24h safety margin AND eleven old headers. */
    public static boolean hasBoundary(List<Block> chain, long midnight) {
        if (chain.isEmpty()) return false;
        Block last = chain.get(chain.size() - 1);
        if (last.height == 0) return true;
        if (chain.size() < 11 || last.timestamp >= midnight - BOUNDARY_MARGIN) return false;
        for (int i = chain.size() - 11; i < chain.size(); i++) {
            if (chain.get(i).timestamp >= midnight - BOUNDARY_MARGIN) return false;
        }
        return true;
    }
    public static int count(List<Block> chain, DayMath.Day day) {
        if (!hasBoundary(chain, day.start)) throw new IllegalStateException("Incomplete day history");
        int count = 0;
        for (Block b : chain) if (b.timestamp >= day.start && b.timestamp < day.end) count++;
        return count;
    }
}
