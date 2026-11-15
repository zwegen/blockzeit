package de.blockzeit.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Provider Instances use milliseconds. All-day values are UTC date labels, not local instants. */
public final class CalendarAgenda {
    private CalendarAgenda() { }
    public static final class Entry {
        public final long id, begin, end;
        public final String title;
        public final boolean allDay;
        public Entry(long id, long begin, long end, String title, boolean allDay) {
            this.id = id; this.begin = begin; this.end = end; this.allDay = allDay;
            String clean = singleLine(title == null ? "" : title);
            this.title = clean.isEmpty() ? "Untitled" : clean;
        }
        public LocalDate date(ZoneId zone) {
            return Instant.ofEpochMilli(begin).atZone(allDay ? ZoneOffset.UTC : zone).toLocalDate();
        }
        public boolean active(long now, ZoneId zone) {
            if (allDay) return Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                    .isAfter(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
            return end > now || end == begin && begin >= now;
        }
        /** All-day END is an exclusive UTC date label, ending at local midnight. */
        public long endedAt(ZoneId zone) {
            return allDay ? Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli() : end;
        }
        public boolean ended(long now, ZoneId zone) {
            return end >= begin && endedAt(zone) <= now && !active(now, zone);
        }
        long start(ZoneId zone) { return allDay ? date(zone).atStartOfDay(zone).toInstant().toEpochMilli() : begin; }
        public String dateText(ZoneId zone) {
            return date(zone).format(DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ENGLISH));
        }
        public String when(long now, ZoneId zone) {
            return dateText(zone) + " · " + (allDay ? "All day" : Instant.ofEpochMilli(begin).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)));
        }
        public String following(ZoneId zone) { return dateText(zone) + " · " + title; }
    }
    public static String singleLine(String value) { return value.replaceAll("[\\p{Z}\\s\\p{Cc}]+", " ").trim(); }
    public static List<Entry> upcoming(List<Entry> input, long now, ZoneId zone) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : input) if (entry.end >= entry.begin && entry.active(now, zone)) result.add(entry);
        result.sort(Comparator.comparingLong((Entry e) -> e.start(zone)).thenComparingLong(e -> e.id));
        return result;
    }
}
