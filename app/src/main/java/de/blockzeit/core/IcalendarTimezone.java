package de.blockzeit.core;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded VTIMEZONE reader. A recognized TZID alone is never evidence that its rules are safe.
 * Call {@link Definition#validate(int, int)} before using the corresponding IANA zone for events.
 * Unsupported properties, parameters and recurrence syntax fail closed.
 */
public final class IcalendarTimezone {
    private static final int MAX_COMPONENTS = 32;
    private static final int MAX_LINES = 4096;
    private static final int MAX_CHARACTERS = 1024 * 1024;
    private static final int MAX_OBSERVANCES = 32;
    private static final int MAX_RDATES = 2048;
    private static final int MAX_YEAR_EVALUATIONS = 50000;
    private static final int MAX_TRANSITIONS = 100000;
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DAY = Pattern.compile("([+-]?[1-5])?(MO|TU|WE|TH|FR|SA|SU)");

    private IcalendarTimezone() { }

    /**
     * Components contain unfolded content lines, including their BEGIN/END:VTIMEZONE wrappers.
     * Syntax and zone identities are checked for every component, even if no event uses it.
     * The returned map and the definitions are independent of the caller's mutable input.
     */
    public static Map<String, Definition> parse(List<List<String>> components) throws IllegalArgumentException {
        return parseSelected(components, null);
    }
    /** Unused custom timezone semantics cannot affect explicitly zoned/UTC appointments.
     * Structural/text/component limits still cover the complete timezone payload. */
    static Map<String, Definition> parseReferenced(List<List<String>> components, Set<String> referenced) {
        return parseSelected(components, referenced);
    }
    private static Map<String, Definition> parseSelected(List<List<String>> components, Set<String> referenced) {
        if (components == null || components.size() > MAX_COMPONENTS) throw invalid("too many components");
        Map<String, Definition> result = new LinkedHashMap<>();
        int lines = 0, characters = 0;
        for (List<String> component : components) {
            if (component == null || component.size() < 3 || component.size() > MAX_LINES - lines)
                throw invalid("invalid component size");
            lines += component.size();
            for (String line : component) {
                if (line == null || line.isEmpty() || line.length() > 4096
                        || line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0 || line.indexOf('\0') >= 0)
                    throw invalid("invalid unfolded line");
                characters += line.length();
                if (characters > MAX_CHARACTERS) throw invalid("component text limit");
            }
            if(referenced != null) {
                String id=null;int depth=0,observances=0,rdates=0;
                for(String raw:component) {
                    Property property=new Property(raw);
                    if(property.name.equals("BEGIN")){depth++;if(property.value.equalsIgnoreCase("STANDARD")||property.value.equalsIgnoreCase("DAYLIGHT"))observances++;}
                    else if(property.name.equals("END"))depth--;
                    else if(property.name.equals("TZID")&&depth==1){property.noParameters();if(id!=null)throw invalid("duplicate TZID");id=property.value;}
                    else if(property.name.equals("RDATE"))rdates+=property.value.split(",",-1).length;
                }
                if(id==null)throw invalid("missing TZID");
                if(observances>MAX_OBSERVANCES||rdates>MAX_RDATES)throw invalid("timezone observance/date limit");
                if(!referenced.contains(id))continue;
            }
            Definition definition = readComponent(component);
            if (result.put(definition.tzid, definition) != null) throw invalid("duplicate TZID");
        }
        return Collections.unmodifiableMap(result);
    }

    private static Definition readComponent(List<String> lines) {
        if (!lines.get(0).equalsIgnoreCase("BEGIN:VTIMEZONE")
                || !lines.get(lines.size() - 1).equalsIgnoreCase("END:VTIMEZONE"))
            throw invalid("missing VTIMEZONE wrapper");
        String tzid = null;
        Set<String> seen = new HashSet<>();
        List<Observance> observances = new ArrayList<>();
        Builder current = null;
        for (int i = 1; i < lines.size() - 1; i++) {
            Property property = new Property(lines.get(i));
            String name = property.name;
            String value = property.value;
            if (name.equals("BEGIN")) {
                property.noParameters();
                if (current != null || !(value.equalsIgnoreCase("STANDARD") || value.equalsIgnoreCase("DAYLIGHT")))
                    throw invalid("unexpected nested component");
                if (observances.size() >= MAX_OBSERVANCES) throw invalid("observance limit");
                current = new Builder(value.toUpperCase(Locale.ROOT));
            } else if (name.equals("END")) {
                property.noParameters();
                if (current == null || !current.kind.equalsIgnoreCase(value)) throw invalid("unbalanced observance");
                observances.add(current.build());
                current = null;
            } else if (current != null) {
                current.add(property);
            } else {
                property.noParameters();
                if (!seen.add(name)) throw invalid("duplicate timezone property");
                switch (name) {
                    case "TZID":
                        try {
                            ZoneId zone = ZoneId.of(value);
                            if (!ZONES.contains(value) && !zone.getRules().isFixedOffset()) throw invalid("unknown IANA TZID");
                        } catch (java.time.DateTimeException bad) { throw invalid("unknown IANA TZID"); }
                        tzid = value;
                        break;
                    case "LAST-MODIFIED": utc(value); break;
                    case "TZURL": break;
                    // These labels do not alter an observance's instants or offsets.
                    case "X-LIC-LOCATION":
                        if (value.isEmpty()) throw invalid("empty location label");
                        break;
                    default: if (!name.startsWith("X-")) throw invalid("unsupported timezone property: " + name);
                }
            }
        }
        if (current != null || tzid == null || observances.isEmpty()) throw invalid("incomplete VTIMEZONE");
        int rdates = 0;
        for (Observance observance : observances) {
            rdates += observance.dates.size();
            if (rdates > MAX_RDATES) throw invalid("RDATE limit");
        }
        return new Definition(tzid, observances);
    }

    public static final class Definition {
        private final String tzid;
        private final List<Observance> observances;
        // Only proven, contiguous intervals are cached; a cache cannot bridge an unchecked gap.
        private int checkedFirst = 10000, checkedLast = 0;

        private Definition(String tzid, List<Observance> observances) {
            this.tzid = tzid;
            this.observances = Collections.unmodifiableList(new ArrayList<>(observances));
        }

        /**
         * Prove wall-offset equivalence for every instant in the inclusive UTC calendar years.
         * Bounds must be 1..9999. For indefinite recurrence pass 9999, not a short sample horizon.
         * Historical differences outside this interval are allowed, but its entering offset and
         * every effective source transition within it must match the installed IANA ZoneRules.
         */
        public synchronized void validate(int firstYear, int lastYear) throws IllegalArgumentException {
            if (firstYear < 1 || lastYear > 9999 || firstYear > lastYear) throw invalid("invalid validation years");
            if (firstYear >= checkedFirst && lastYear <= checkedLast) return;
            Instant begin = LocalDate.of(firstYear, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant end = LocalDate.of(lastYear + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
            TreeMap<Instant, Change> changes = new TreeMap<>();
            int yearEvaluations = 0;
            for (Observance observance : observances) {
                add(changes, observance, observance.start);
                for (LocalDateTime date : observance.dates) add(changes, observance, date);
                Annual annual = observance.annual;
                if (annual == null) continue;
                // A positive offset can put an onset in January of the next local year just
                // before the UTC end boundary, so that next local year must also be generated.
                int finalYear = lastYear + 1;
                if (annual.until != null) finalYear = Math.min(finalYear,
                        annual.until.atOffset(ZoneOffset.UTC).getYear() + 1);
                int count = Math.max(0, finalYear - observance.start.getYear() + 1);
                yearEvaluations += count;
                if (yearEvaluations > MAX_YEAR_EVALUATIONS) throw invalid("recurrence computation limit");
                for (int year = observance.start.getYear(); year <= finalYear; year++) {
                    LocalDate month = LocalDate.of(year, annual.month, 1);
                    for (int day = 1; day <= month.lengthOfMonth(); day++) {
                        LocalDate date = month.withDayOfMonth(day);
                        if (!annual.matches(date)) continue;
                        LocalDateTime onset = date.atTime(observance.start.toLocalTime());
                        if (onset.isBefore(observance.start)) continue;
                        Instant instant = onset.toInstant(observance.from);
                        if (annual.until != null && instant.isAfter(annual.until)) continue;
                        add(changes, observance, onset);
                    }
                }
            }
            // RFC 5545: before the earliest onset the earliest observance's TZOFFSETFROM applies.
            ZoneOffset offset = changes.firstEntry().getValue().from;
            for (Map.Entry<Instant, Change> entry : changes.headMap(begin, false).entrySet()) {
                Change change = entry.getValue();
                if (!offset.equals(change.from)) throw invalid("inconsistent source offsets");
                offset = change.to;
            }
            ZoneRules rules = ZoneId.of(tzid).getRules();
            if (!offset.equals(rules.getOffset(begin.minusNanos(1)))) throw invalid("entering offset differs from IANA");
            ZoneOffsetTransition actual = rules.nextTransition(begin.minusNanos(1));
            for (Map.Entry<Instant, Change> entry : changes.subMap(begin, true, end, false).entrySet()) {
                Change change = entry.getValue();
                if (!offset.equals(change.from)) throw invalid("inconsistent source offsets");
                offset = change.to;
                if (change.from.equals(change.to)) continue; // A fixed-zone declaration is not a clock change.
                if (actual == null || !entry.getKey().equals(actual.getInstant())
                        || !change.from.equals(actual.getOffsetBefore()) || !change.to.equals(actual.getOffsetAfter()))
                    throw invalid("source transition differs from IANA");
                actual = rules.nextTransition(actual.getInstant());
            }
            if (actual != null && actual.getInstant().isBefore(end)) throw invalid("missing IANA transition");
            if (checkedLast != 0 && firstYear <= checkedLast + 1 && lastYear >= checkedFirst - 1) {
                checkedFirst = Math.min(firstYear, checkedFirst);
                checkedLast = Math.max(lastYear, checkedLast);
            } else {
                checkedFirst = firstYear;
                checkedLast = lastYear;
            }
        }
    }

    private static void add(TreeMap<Instant, Change> changes, Observance observance, LocalDateTime onset) {
        Instant instant = onset.toInstant(observance.from);
        Change change = new Change(observance.from, observance.to);
        Change previous = changes.putIfAbsent(instant, change);
        if (previous != null && (!previous.from.equals(change.from) || !previous.to.equals(change.to)))
            throw invalid("conflicting simultaneous onsets");
        if (changes.size() > MAX_TRANSITIONS) throw invalid("transition limit");
    }

    private static final class Change {
        final ZoneOffset from, to;
        Change(ZoneOffset from, ZoneOffset to) { this.from = from; this.to = to; }
    }

    private static final class Observance {
        final LocalDateTime start;
        final ZoneOffset from, to;
        final List<LocalDateTime> dates;
        final Annual annual;
        Observance(LocalDateTime start, ZoneOffset from, ZoneOffset to, List<LocalDateTime> dates, Annual annual) {
            this.start = start;
            this.from = from;
            this.to = to;
            this.dates = Collections.unmodifiableList(new ArrayList<>(dates));
            this.annual = annual;
        }
    }

    private static final class Builder {
        final String kind;
        final Set<String> seen = new HashSet<>();
        final List<LocalDateTime> dates = new ArrayList<>();
        LocalDateTime start;
        ZoneOffset from, to;
        String rule;
        Builder(String kind) { this.kind = kind; }

        void add(Property property) {
            String name = property.name;
            if (!name.equals("RDATE") && !name.equals("TZNAME") && !seen.add(name))
                throw invalid("duplicate observance property");
            switch (name) {
                case "DTSTART":
                    property.dateParameters();
                    start = local(property.value);
                    break;
                case "TZOFFSETFROM": property.noParameters(); from = offset(property.value); break;
                case "TZOFFSETTO": property.noParameters(); to = offset(property.value); break;
                case "RRULE": property.noParameters(); rule = property.value; break;
                case "RDATE":
                    property.dateParameters();
                    String[] values = property.value.split(",", -1);
                    if (values.length > MAX_RDATES - dates.size()) throw invalid("RDATE limit");
                    for (String value : values) dates.add(local(value));
                    break;
                case "TZNAME":
                    if (!property.parameters.isEmpty()
                            && !property.parameters.matches("LANGUAGE=(?:[A-Za-z0-9-]{1,35}|\"[A-Za-z0-9-]{1,35}\")"))
                        throw invalid("unsupported TZNAME parameters");
                    if (property.value.isEmpty()) throw invalid("empty TZNAME");
                    break;
                default: if (!name.startsWith("X-")) throw invalid("unsupported observance property: " + name);
            }
        }

        Observance build() {
            if (start == null || from == null || to == null) throw invalid("incomplete observance");
            for (LocalDateTime date : dates) if (date.isBefore(start)) throw invalid("RDATE before DTSTART");
            Annual annual = rule == null ? null : new Annual(rule);
            if (annual != null && (!annual.matches(start.toLocalDate())
                    || (annual.until != null && start.toInstant(from).isAfter(annual.until))))
                throw invalid("DTSTART is inconsistent with RRULE");
            return new Observance(start, from, to, dates, annual);
        }
    }

    /** Single-month yearly rules: BYDAY (one weekday, optionally ordinal), BYMONTHDAY, or both. */
    private static final class Annual {
        final int month;
        final Set<Integer> monthDays = new HashSet<>();
        final DayOfWeek weekday;
        final int ordinal;
        final Instant until;

        Annual(String value) {
            Map<String, String> parts = new HashMap<>();
            for (String field : value.toUpperCase(Locale.ROOT).split(";", -1)) {
                int equals = field.indexOf('=');
                if (equals <= 0 || equals == field.length() - 1 || field.indexOf('=', equals + 1) >= 0)
                    throw invalid("malformed RRULE");
                String key = field.substring(0, equals), item = field.substring(equals + 1);
                if (parts.put(key, item) != null) throw invalid("duplicate RRULE part");
                if (!(key.equals("FREQ") || key.equals("INTERVAL") || key.equals("BYMONTH")
                        || key.equals("BYDAY") || key.equals("BYMONTHDAY") || key.equals("UNTIL")))
                    throw invalid("unsupported RRULE part: " + key);
            }
            if (!"YEARLY".equals(parts.get("FREQ")) || (parts.containsKey("INTERVAL")
                    && !"1".equals(parts.get("INTERVAL")))) throw invalid("unsupported recurrence frequency");
            month = number(parts.get("BYMONTH"), 1, 12);
            if (parts.containsKey("BYMONTHDAY")) {
                String[] days = parts.get("BYMONTHDAY").split(",", -1);
                if (days.length > 62) throw invalid("BYMONTHDAY limit");
                for (String day : days) {
                    int number = number(day, -31, 31);
                    if (number == 0 || !monthDays.add(number)) throw invalid("invalid BYMONTHDAY");
                }
            }
            if (parts.containsKey("BYDAY")) {
                Matcher match = DAY.matcher(parts.get("BYDAY"));
                if (!match.matches()) throw invalid("unsupported BYDAY");
                ordinal = match.group(1) == null ? 0 : Integer.parseInt(match.group(1));
                weekday = weekday(match.group(2));
            } else {
                ordinal = 0;
                weekday = null;
            }
            if (weekday == null && monthDays.isEmpty()) throw invalid("missing yearly date selector");
            until = parts.containsKey("UNTIL") ? utc(parts.get("UNTIL")) : null;
        }

        boolean matches(LocalDate date) {
            if (date.getMonthValue() != month) return false;
            int day = date.getDayOfMonth(), length = date.lengthOfMonth();
            if (!monthDays.isEmpty() && !(monthDays.contains(day) || monthDays.contains(day - length - 1))) return false;
            if (weekday != null && date.getDayOfWeek() != weekday) return false;
            if (ordinal > 0 && (day - 1) / 7 + 1 != ordinal) return false;
            return ordinal >= 0 || -((length - day) / 7 + 1) == ordinal;
        }
    }

    private static final class Property {
        final String name, parameters, value;
        Property(String line) {
            int colon = line.indexOf(':');
            if (colon <= 0) throw invalid("malformed content line");
            String header = line.substring(0, colon);
            int semicolon = header.indexOf(';');
            name = (semicolon < 0 ? header : header.substring(0, semicolon)).toUpperCase(Locale.ROOT);
            parameters = semicolon < 0 ? "" : header.substring(semicolon + 1).toUpperCase(Locale.ROOT);
            if (!name.matches("[A-Z][A-Z0-9-]*") || (semicolon >= 0 && parameters.isEmpty()))
                throw invalid("malformed property header");
            value = line.substring(colon + 1);
        }
        void noParameters() {
            if (!parameters.isEmpty()) throw invalid("unsupported property parameters");
        }
        void dateParameters() {
            if (!parameters.isEmpty() && !parameters.equals("VALUE=DATE-TIME"))
                throw invalid("observance dates must be local DATE-TIME");
        }
    }

    private static LocalDateTime local(String value) {
        if (!value.matches("[0-9]{8}T[0-9]{6}")) throw invalid("invalid local DATE-TIME");
        try {
            LocalDateTime date = LocalDateTime.parse(value, TIME);
            if (date.getYear() < 1 || date.getYear() > 9999) throw invalid("DATE-TIME year limit");
            return date;
        } catch (DateTimeException e) { throw invalid("invalid local DATE-TIME"); }
    }

    private static Instant utc(String value) {
        if (!value.endsWith("Z")) throw invalid("UTC DATE-TIME required");
        return local(value.substring(0, value.length() - 1)).toInstant(ZoneOffset.UTC);
    }

    private static ZoneOffset offset(String value) {
        if (!value.matches("[+-][0-9]{4}(?:[0-9]{2})?") || value.equals("-0000") || value.equals("-000000"))
            throw invalid("invalid UTC offset");
        int hour = Integer.parseInt(value.substring(1, 3));
        int minute = Integer.parseInt(value.substring(3, 5));
        int second = value.length() == 7 ? Integer.parseInt(value.substring(5, 7)) : 0;
        if (minute > 59 || second > 59) throw invalid("invalid UTC offset");
        int total = (hour * 3600 + minute * 60 + second) * (value.charAt(0) == '-' ? -1 : 1);
        try { return ZoneOffset.ofTotalSeconds(total); }
        catch (DateTimeException e) { throw invalid("invalid UTC offset"); }
    }

    private static int number(String value, int minimum, int maximum) {
        if (value == null || !value.matches("[+-]?[0-9]{1,2}")) throw invalid("invalid recurrence number");
        int result = Integer.parseInt(value);
        if (result < minimum || result > maximum) throw invalid("recurrence number out of range");
        return result;
    }

    private static DayOfWeek weekday(String value) {
        switch (value) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: throw invalid("invalid weekday");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("VTIMEZONE: " + reason);
    }
}
