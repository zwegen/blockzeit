package de.blockzeit;

import de.blockzeit.core.Block;
import de.blockzeit.core.DayMath;
import de.blockzeit.core.History;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** A historical DISPLAY frame, never a claim that acquisition data is still fresh. */
@android.annotation.TargetApi(31)
final class RingFrame {
    final int actual;
    final long asOf, checked;
    final String zone, tip;

    private RingFrame(int actual, long asOf, String zone, long checked, String tip) {
        if (actual < 0 || actual > History.MAX_HEADERS || checked < 0 || asOf < checked
                || !Block.isHash(tip) || zone.length() > 80) throw new IllegalArgumentException("Ring frame");
        this.actual = actual; this.asOf = asOf; this.zone = ZoneId.of(zone).getId();
        this.checked = checked; this.tip = tip;
        day(); // Reject out-of-range dates before a persisted frame can be used.
    }
    DayMath.Day day() { return DayMath.at(asOf, ZoneId.of(zone)); }

    static RingFrame select(RingFrame previous, long now, ZoneId zone, boolean fresh,
                            int actual, long checked, String sourceDay, String tip) {
        if (!fresh) return previous;
        RingFrame next = new RingFrame(actual, now, zone.getId(), checked, tip);
        if (!next.day().key.equals(sourceDay)) return previous;
        // A backwards clock must not rewind a frame made from the SAME cached acquisition.
        // A different successful acquisition may replace it, even after a wall-clock correction.
        if (previous != null && previous.checked == checked && previous.tip.equals(tip)
                && previous.day().key.equals(sourceDay) && previous.asOf > now) return previous;
        return next;
    }
    /** First-upgrade fallback: data-as-of only, NOT a reconstruction of the previous UI frame. */
    static RingFrame bootstrap(Store.Snapshot snapshot) {
        try {
            Block.parseTimestamp(Long.toString(snapshot.checked));
            if (snapshot.dayKey == null || snapshot.dayKey.length() > 100) return null;
            int separator = snapshot.dayKey.indexOf('@');
            if (separator < 1) return null;
            ZoneId originalZone = ZoneId.of(snapshot.dayKey.substring(separator + 1));
            DayMath.Day originalDay = DayMath.at(snapshot.checked, originalZone);
            if (!snapshot.fresh(snapshot.checked, originalDay)) return null;
            // Recheck linkage/limit as well as the complete original-day boundary.
            List<Block> verified = new ArrayList<>();
            for (Block block : snapshot.blocks) History.append(verified, block);
            return select(null, snapshot.checked, originalZone, true, History.count(verified, originalDay),
                    snapshot.checked, snapshot.dayKey, verified.get(0).hash);
        } catch (RuntimeException invalid) { return null; }
    }
    String encode() { return "1|" + actual + "|" + asOf + "|" + zone + "|" + checked + "|" + tip; }
    static RingFrame restore(String raw, long maxSampleAge) {
        if (raw == null || raw.length() > 256) return null;
        try {
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("1") || !fields[1].matches("[0-9]{1,3}")
                    || !fields[2].matches("[0-9]{1,12}") || !fields[4].matches("[0-9]{1,12}")) return null;
            RingFrame frame = new RingFrame(Integer.parseInt(fields[1]), Long.parseLong(fields[2]),
                    fields[3], Long.parseLong(fields[4]), fields[5]);
            // Validate the frame's ORIGINAL acquisition age, not its age at restoration.
            return frame.asOf - frame.checked <= maxSampleAge ? frame : null;
        } catch (RuntimeException invalid) { return null; }
    }
}
