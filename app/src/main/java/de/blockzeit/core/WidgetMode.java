package de.blockzeit.core;

/** Preserve the original mode values and read legacy stored widget modes safely. */
public final class WidgetMode {
    public static final int CLOCK = 0, BLOCK = 1, MARKET = 2, WEATHER = 3, CALENDAR = 5; // 4 is a retired mode: never reinterpret old stored data.
    private WidgetMode() { }
    public static int read(Object stored) {
        if (stored instanceof Boolean) return (Boolean) stored ? BLOCK : CLOCK;
        if (stored instanceof Integer) {
            int mode = (Integer) stored;
            if (mode == CLOCK || mode == BLOCK || mode == MARKET || mode == WEATHER || mode == CALENDAR) return mode;
        }
        return CLOCK;
    }
    /** If a corrupt/imported configuration disables both base modes, safely keep Clock. */
    public static boolean clockAllowed(boolean clock, boolean block) { return clock || !block; }
    public static boolean enabled(int mode, boolean clock, boolean block, boolean price, boolean weather, boolean calendar) {
        switch (mode) {
            case CLOCK: return clockAllowed(clock, block);
            case BLOCK: return block;
            case MARKET: return price;
            case WEATHER: return weather;
            case CALENDAR: return calendar;
            default: return false;
        }
    }
    public static int normalize(Object stored, boolean clock, boolean block, boolean price, boolean weather, boolean calendar) {
        int mode = read(stored);
        return enabled(mode, clock, block, price, weather, calendar) ? mode : next(mode, clock, block, price, weather, calendar);
    }
    public static int next(int mode, boolean clock, boolean block, boolean price, boolean weather, boolean calendar) {
        return cycle(mode, clock, block, price, weather, calendar, false);
    }
    public static int previous(int mode, boolean clock, boolean block, boolean price, boolean weather, boolean calendar) {
        return cycle(mode, clock, block, price, weather, calendar, true);
    }
    private static int cycle(int mode, boolean clock, boolean block, boolean price, boolean weather, boolean calendar, boolean reverse) {
        int[] order = {CLOCK, BLOCK, MARKET, WEATHER, CALENDAR};
        int index = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == read(mode)) index = i;
        do { index = (index + (reverse ? order.length - 1 : 1)) % order.length; }
        while (!enabled(order[index], clock, block, price, weather, calendar));
        return order[index];
    }
    public static boolean enabled(int mode, boolean price, boolean weather, boolean calendar) {
        return mode == CALENDAR ? calendar : enabled(mode, price, weather);
    }
    public static int normalize(Object stored, boolean price, boolean weather, boolean calendar) {
        int mode = read(stored);
        return enabled(mode, price, weather, calendar) ? mode : next(mode, price, weather, calendar);
    }
    public static int next(int mode, boolean price, boolean weather, boolean calendar) {
        int candidate = read(mode);
        do {
            switch (candidate) {
                case CLOCK: candidate = BLOCK; break;
                case BLOCK: candidate = MARKET; break;
                case MARKET: candidate = WEATHER; break;
                case WEATHER: candidate = CALENDAR; break;
                default: candidate = CLOCK;
            }
        } while (!enabled(candidate, price, weather, calendar));
        return candidate;
    }
    public static int previous(int mode, boolean price, boolean weather, boolean calendar) {
        int candidate = read(mode);
        do {
            switch (candidate) {
                case CLOCK: candidate = CALENDAR; break;
                case BLOCK: candidate = CLOCK; break;
                case MARKET: candidate = BLOCK; break;
                case WEATHER: candidate = MARKET; break;
                default: candidate = WEATHER;
            }
        } while (!enabled(candidate, price, weather, calendar));
        return candidate;
    }
    public static int next(int mode) { return next(mode, true, true); }
    public static boolean enabled(int mode, boolean price, boolean weather) {
        return mode == CLOCK || mode == BLOCK || mode == MARKET && price || mode == WEATHER && weather;
    }
    public static int normalize(Object stored, boolean price, boolean weather) {
        int mode = read(stored);
        return enabled(mode, price, weather) ? mode : next(mode, price, weather);
    }
    public static int next(int mode, boolean price, boolean weather) {
        int next = read(mode);
        // Keep persisted IDs stable; only the display/cycling order changes.
        do {
            switch (next) {
                case CLOCK: next = BLOCK; break;
                case BLOCK: next = MARKET; break;
                case MARKET: next = WEATHER; break;
                default: next = CLOCK;
            }
        } while (!enabled(next, price, weather));
        return next;
    }
}
