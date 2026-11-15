package de.blockzeit.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.zone.ZoneOffsetTransition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, fail-closed importer for a deliberately bounded iCalendar recurrence subset. */
public final class Icalendar {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_LINE_BYTES = 16 * 1024;
    public static final int MAX_LINES = 50000;
    public static final int MAX_EVENTS = 2000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile("\\+?P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)");
    public static final int MAX_RECURRENCE_DATES = 1024;
    private static final Set<String> WEEKDAYS = set("MO", "TU", "WE", "TH", "FR", "SA", "SU");
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    private Icalendar() { }

    public static final class Event {
        public final String uid, title, description, location, zone;
        /** Canonical provider recurrence fields; empty for a non-recurring event. */
        public final String rrule, rdate, exdate, duration;
        /** Epoch milliseconds; all-day values are exclusive UTC date labels, not local instants. */
        public final long start, end;
        public final boolean allDay;
        /** Native-compatible availability: busy=0, free=1, tentative=2. */
        public final int availability;
        /** Validated archival provenance, NEVER an effective event end. */
        public final Long archivedEnd;
        public final String archivedEndZone;
        public final String endZone, organizer;
        public final int status, access;
        public final Map<String, Integer> nativeProperties;
        public final Long recurrenceId;
        public final boolean recurrenceAllDay;
        public final String recurrenceZone;
        public final List<Attendee> attendees;

        private Event(String uid, String title, String description, String location, Moment start, long end,
                String rrule, String rdate, String exdate, String duration, int availability, Long archivedEnd, String archivedEndZone, Metadata metadata) {
            this.uid = uid; this.title = title; this.description = description; this.location = location;
            this.zone = start.zone.getId(); this.start = start.millis; this.end = end; this.allDay = start.date;
            this.rrule = rrule; this.rdate = rdate; this.exdate = exdate; this.duration = duration;
            this.availability = availability; this.archivedEnd = archivedEnd; this.archivedEndZone = archivedEndZone;
            this.endZone = metadata.endZone; this.organizer = metadata.organizer;
            this.status = metadata.status; this.access = metadata.access;
            this.nativeProperties = Collections.unmodifiableMap(new TreeMap<>(metadata.nativeProperties));
            this.recurrenceId = metadata.original == null ? null : metadata.original.millis;
            this.recurrenceAllDay = metadata.original != null && metadata.original.date;
            this.recurrenceZone = metadata.original == null ? "" : metadata.original.zone.getId();
            this.attendees = Collections.unmodifiableList(new ArrayList<>(metadata.attendees));
        }

        /** Stable original-occurrence identity; never the moved DTSTART or a source provider row ID. */
        public String sourceIdentity() { return recurrenceId == null ? uid : "recurrence-id-1:" + uid.length() + ":" + uid + ":" + recurrenceAllDay + ":" + recurrenceId; }

        public boolean recurring() { return !rrule.isEmpty() || !rdate.isEmpty(); }

        /** Length-delimited, versioned canonical fields; independent of input order, folding and escaping. */
        public String fingerprint() { return fingerprint(false); }
        /** Read-only compatibility for journals written by the previous bounded importer. */
        public String legacyFingerprint() { return fingerprint(true); }
        public boolean legacyCompatible() { return recurrenceId == null && availability == 0 && attendees.isEmpty()
                && organizer.isEmpty() && endZone.equals(zone) && (status == -1 || status == 1) && (access == 0 || access == 3)
                && !nativeProperties.containsKey("X-ANDROID-EVENT-COLOR")
                && nativeProperties.getOrDefault("X-ANDROID-GUESTS-CAN-MODIFY",0) == 0
                && nativeProperties.getOrDefault("X-ANDROID-GUESTS-CAN-INVITE-OTHERS",1) == 1
                && nativeProperties.getOrDefault("X-ANDROID-GUESTS-CAN-SEE-GUESTS",1) == 1; }
        private String fingerprint(boolean legacy) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                List<String> fields = new ArrayList<>();
                Collections.addAll(fields, "blockzeit-ical-1", uid, title, description, location, zone,
                        Long.toString(start), Long.toString(end), Boolean.toString(allDay));
                // Preserve existing non-recurring identities. Series additionally include every effective field.
                if (recurring()) Collections.addAll(fields, "recurrence-1", rrule, rdate, exdate, duration);
                // Keep historical BUSY fingerprints/journals readable; newly supported semantics are tagged.
                if (!legacy) {
                if (availability != 0) Collections.addAll(fields, "availability-1", Integer.toString(availability));
                if (!endZone.equals(zone)) Collections.addAll(fields, "end-zone-1", endZone);
                if (status != 1) Collections.addAll(fields, "status-1", Integer.toString(status));
                if (access != 3) Collections.addAll(fields, "access-1", Integer.toString(access));
                if (recurrenceId != null) Collections.addAll(fields, "exception-1", Long.toString(recurrenceId), Boolean.toString(recurrenceAllDay));
                if (!organizer.isEmpty()) Collections.addAll(fields, "organizer-1", organizer);
                for (Map.Entry<String, Integer> property : nativeProperties.entrySet()) Collections.addAll(fields, property.getKey(), property.getValue().toString());
                for (Attendee attendee : attendees) Collections.addAll(fields, "attendee-1", attendee.canonical());
                }
                for (String value : fields) {
                    byte[] bytes = Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'),
                            Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
                    digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
                    digest.update(bytes);
                }
                StringBuilder hex = new StringBuilder(64);
                for (byte b : digest.digest()) hex.append(Character.forDigit((b >>> 4) & 15, 16))
                        .append(Character.forDigit(b & 15, 16));
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    public static final class Document {
        public final List<Event> events;
        public final List<String> rejected;
        /** Number of observed VEVENT starts, including rejected events (bounded at MAX_EVENTS + 1). */
        public final int totalCount;
        /** False means the entire document was rejected and events is empty. */
        public final boolean valid;
        /** Provider-specific eligibility may reject whole groups, never silently trim exceptions. */
        public Document withRejectedGroups(Set<String> uids,String reason) {
            List<Event> supported=new ArrayList<>();List<String> diagnostics=new ArrayList<>(rejected);
            for(Event event:events) {
                if(uids.contains(event.uid))diagnostics.add("VEVENT: "+reason);
                else supported.add(event);
            }
            return new Document(supported,diagnostics,totalCount,valid);
        }
        private Document(List<Event> events, List<String> rejected, int totalCount, boolean valid) {
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(rejected));
            this.totalCount = totalCount; this.valid = valid;
        }
    }

    /**
     * Does not close the caller's stream. I/O errors propagate; cancellation throws CancellationException.
     * Malformed/unsupported input is returned as explicit diagnostics. A document-level failure discards
     * every event; an event-level failure leaves independent supported events available for preview.
     */
    public static Document parse(InputStream input, ZoneId floatingZone, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(floatingZone, "floatingZone");
        Objects.requireNonNull(cancelled, "cancelled");
        List<Line> lines = new ArrayList<>();
        int total = 0;
        try {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                check(cancelled);
                int size = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - data.size()));
                if (size < 0) break;
                if (size == 0) {
                    int one = input.read();
                    if (one < 0) break;
                    data.write(one);
                } else data.write(buffer, 0, size);
                require(data.size() <= MAX_BYTES, "Input exceeds 2 MiB byte limit");
            }
            check(cancelled);
            byte[] bytes = data.toByteArray();
            // Count the BOM in MAX_BYTES, but remove exactly one initial UTF-8 signature.
            int position = bytes.length >= 3 && (bytes[0] & 255) == 0xef
                    && (bytes[1] & 255) == 0xbb && (bytes[2] & 255) == 0xbf ? 3 : 0;
            int physical = 0, logicalNumber = 0;
            ByteArrayOutputStream logical = null;
            while (position < bytes.length) {
                check(cancelled);
                require(++physical <= MAX_LINES, "Physical line count exceeds 50000");
                int next = position;
                while (next < bytes.length && bytes[next] != '\n') next++;
                int finish = next;
                if (next < bytes.length && finish > position && bytes[finish - 1] == '\r') finish--;
                boolean folded = bytes[position] == ' ' || bytes[position] == '\t';
                int begin = position;
                if (folded) {
                    require(logical != null, "Orphan folded line " + physical);
                    begin++;
                } else {
                    if (logical != null) {
                        Line line = logicalLine(logical, logicalNumber);
                        lines.add(line);
                        if (line.is("BEGIN", "VEVENT")) require(++total <= MAX_EVENTS, "Event count exceeds 2000");
                    }
                    logical = new ByteArrayOutputStream(); logicalNumber = physical;
                }
                require(logical.size() + finish - begin <= MAX_LINE_BYTES, "Unfolded line exceeds 16 KiB at line " + logicalNumber);
                logical.write(bytes, begin, finish - begin);
                position = next < bytes.length ? next + 1 : bytes.length;
            }
            if (logical != null) {
                Line line = logicalLine(logical, logicalNumber);
                lines.add(line);
                if (line.is("BEGIN", "VEVENT")) require(++total <= MAX_EVENTS, "Event count exceeds 2000");
            }
            return document(lines, total, floatingZone, cancelled);
        } catch (Invalid invalid) {
            return new Document(Collections.emptyList(), Collections.singletonList("VCALENDAR: " + invalid.getMessage()), total, false);
        }
    }

    private static Document document(List<Line> lines, int total, ZoneId floatingZone, BooleanSupplier cancelled) throws Invalid {
        ArrayDeque<String> stack = new ArrayDeque<>();
        List<Draft> drafts = new ArrayList<>();
        Map<String, Line> calendar = new LinkedHashMap<>();
        Draft current = null;
        List<List<String>> timezones = new ArrayList<>();
        List<String> timezone = null;
        boolean started = false, ended = false;
        String ignoredComponent = null;
        for (Line line : lines) {
            check(cancelled);
            require(!ended, "Content after END:VCALENDAR at line " + line.number);
            if (line.error != null) {
                require(current != null && timezone == null, line.error);
                current.add(line); continue;
            }
            if (line.name.equals("BEGIN") || line.name.equals("END")) {
                require(line.parameters.isEmpty(), "Component parameters are unsupported at line " + line.number);
                String component = line.value.toUpperCase(Locale.ROOT);
                require(component.matches("[A-Z0-9-]+"), "Invalid component at line " + line.number);
                if (timezone != null) timezone.add(line.raw());
                if (line.name.equals("BEGIN")) {
                    if (!started) {
                        require(component.equals("VCALENDAR"), "Root must be VCALENDAR"); started = true;
                    } else if (stack.size() == 1) {
                        require(set("VEVENT", "VTIMEZONE", "VTODO", "VJOURNAL", "VFREEBUSY").contains(component), "Unsupported component " + component);
                        if (component.equals("VTIMEZONE")) {
                            require(timezones.size() < 32, "VTIMEZONE count exceeds 32");
                            timezone = new ArrayList<>(); timezone.add(line.raw()); timezones.add(timezone);
                        } else if(component.equals("VEVENT")) { current = new Draft(drafts.size() + 1); drafts.add(current); }
                        else ignoredComponent = component;
                    } else if (timezone == null && ignoredComponent == null) {
                        require(current != null, "Unsupported component " + component);
                        if (!(stack.size() == 2 && component.equals("VALARM"))) current.reject("Unsupported nested component " + component);
                    }
                    stack.push(component);
                } else {
                    require(!stack.isEmpty() && stack.peek().equals(component), "Mismatched END:" + component + " at line " + line.number);
                    stack.pop();
                    if (component.equals("VEVENT") && stack.size() == 1) current = null;
                    if (component.equals("VTIMEZONE") && stack.size() == 1) timezone = null;
                    if (component.equals(ignoredComponent) && stack.size() == 1) ignoredComponent = null;
                    if (stack.isEmpty()) ended = true;
                }
            } else if (timezone != null) {
                timezone.add(line.raw());
            } else if (ignoredComponent != null) {
                // Balanced non-event components are outside an appointments-only import.
            } else if (current != null) {
                if (stack.size() == 2) current.add(line);
                // Nested component contents are never flattened into the event.
            } else {
                require(started && stack.size() == 1, "Property outside VCALENDAR at line " + line.number);
                if(set("NAME","DESCRIPTION","COLOR","SOURCE","URL","REFRESH-INTERVAL","IMAGE","X-WR-CALNAME","X-WR-CALDESC").contains(line.name)
                        || line.name.startsWith("X-")&&!line.name.equals("X-WR-TIMEZONE"))calendar.putIfAbsent(line.name,line);
                else require(calendar.put(line.name, line) == null, "Duplicate calendar property " + line.name);
            }
        }
        require(started && ended && stack.isEmpty(), "Missing or incomplete VCALENDAR root");
        require(calendar.containsKey("VERSION"), "Missing VERSION:2.0");
        for (Line property : calendar.values()) {
            if (!set("NAME", "DESCRIPTION", "COLOR", "SOURCE", "URL", "REFRESH-INTERVAL", "IMAGE").contains(property.name) && !property.name.startsWith("X-"))
                require(property.parameters.isEmpty(), "Unsupported calendar parameters on " + property.name);
            switch (property.name) {
                case "VERSION": require(property.value.equals("2.0"), "VERSION must be 2.0"); break;
                case "METHOD": require(set("PUBLISH", "REQUEST", "CANCEL").contains(property.value.toUpperCase(Locale.ROOT)), "Unsupported scheduling METHOD (supported: PUBLISH, REQUEST, CANCEL)"); break;
                case "CALSCALE": require(property.value.equalsIgnoreCase("GREGORIAN"), "Unsupported CALSCALE"); break;
                // Optional calendar labels, source links and vendor presentation fields do not create appointments.
                case "PRODID": case "X-WR-CALNAME": case "X-WR-CALDESC": unescape(property.value); break;
                case "NAME": case "DESCRIPTION": case "COLOR": case "SOURCE": case "URL": case "REFRESH-INTERVAL": case "IMAGE": break;
                default: require(property.name.startsWith("X-"), "Unsupported calendar property " + property.name);
            }
        }
        boolean cancellation = calendar.containsKey("METHOD") && calendar.get("METHOD").value.equalsIgnoreCase("CANCEL");
        // A calendar timezone hint only affects otherwise-floating values, never explicitly zoned instants.
        if(calendar.containsKey("X-WR-TIMEZONE")) {
            try { floatingZone = ZoneId.of(unescape(calendar.get("X-WR-TIMEZONE").value)); }
            catch(DateTimeException bad) {
                for(Draft draft:drafts)for(Line line:draft.properties.values())if(set("DTSTART","DTEND","RECURRENCE-ID").contains(line.name)
                        && !line.parameters.containsKey("TZID") && !line.parameters.getOrDefault("VALUE",Collections.emptyList()).contains("DATE")
                        && !line.value.endsWith("Z"))throw new Invalid("Unknown calendar floating timezone");
            }
        }
        int attendeeCount=0;
        for(Draft draft:drafts) attendeeCount+=draft.attendees.size();
        require(attendeeCount<=10000,"Attendee count exceeds document limit (10000)");
        Map<String, IcalendarTimezone.Definition> definitions;
        Set<String> referencedZones=new HashSet<>();
        boolean floating=false;
        for(Draft draft:drafts) {
            List<Line> relevant=new ArrayList<>(draft.properties.values());relevant.addAll(draft.rdates);relevant.addAll(draft.exdates);
            for(Line line:relevant)if(set("DTSTART","DTEND","RECURRENCE-ID","RDATE","EXDATE").contains(line.name)) {
                referencedZones.addAll(line.parameters.getOrDefault("TZID",Collections.emptyList()));
                if(!line.parameters.containsKey("TZID") && !line.parameters.getOrDefault("VALUE",Collections.emptyList()).contains("DATE") && !line.value.endsWith("Z"))floating=true;
            }
        }
        if(floating)referencedZones.add(floatingZone.getId());
        try { definitions = IcalendarTimezone.parseReferenced(timezones,referencedZones); }
        catch (IllegalArgumentException bad) { throw new Invalid("VTIMEZONE: " + bad.getMessage()); }
        Set<String> exceptions = new HashSet<>();
        Map<String, Integer> uidCounts = new HashMap<>();
        for (Draft draft : drafts) {
            check(cancelled);
            for (String uid : draft.uids) {
                uidCounts.put(uid, uidCounts.getOrDefault(uid, 0) + 1);
                if (draft.exception) exceptions.add(uid);
            }
        }
        List<Event> events = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Draft draft : drafts) {
            check(cancelled);
            for (String uid : draft.uids) {
                if (!exceptions.contains(uid) && uidCounts.get(uid) > 1) draft.reject("Duplicate UID is ambiguous");
            }
            Event event = null;
            try {
                event = event(draft, floatingZone, cancellation);
                IcalendarTimezone.Definition definition = definitions.get(sourceZone(draft.properties.get("DTSTART"),floatingZone));
                if (definition != null) {
                    int first = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.start), ZoneId.of(event.zone)).getYear();
                    int last = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.end), ZoneId.of(event.zone)).getYear();
                    // RDATE can precede the master; its source-zone interpretation needs the same proof.
                    if (!event.rdate.isEmpty()) for (String date : event.rdate.split(","))
                        first = Math.min(first, Integer.parseInt(date.substring(0, 4)));
                    definition.validate(Math.max(1, first - 1), event.recurring() ? 9999 : Math.min(9999, last + 1));
                }
            }
            catch (Invalid | DateTimeException | ArithmeticException | IllegalArgumentException bad) { draft.reject(bad.getMessage()); }
            if (event != null) {
                try {
                    IcalendarTimezone.Definition endDefinition = definitions.get(sourceZone(draft.properties.get("DTEND"),floatingZone));
                    if (draft.properties.containsKey("DTEND") && endDefinition != null) { int year = Instant.ofEpochMilli(event.end).atZone(ZoneId.of(event.endZone)).getYear(); endDefinition.validate(Math.max(1, year - 1), Math.min(9999, year + 1)); }
                    if(event.recurrenceId != null) {
                        IcalendarTimezone.Definition anchorDefinition=definitions.get(sourceZone(draft.properties.get("RECURRENCE-ID"),floatingZone));
                        if(anchorDefinition!=null){int year=Instant.ofEpochMilli(event.recurrenceId).atZone(ZoneId.of(event.recurrenceZone)).getYear();anchorDefinition.validate(Math.max(1,year-1),Math.min(9999,year+1));}
                    }
                } catch (IllegalArgumentException bad) { draft.reject(bad.getMessage()); }
            }
            draft.parsed = event;
            if (draft.errors.isEmpty()) events.add(event);
            else rejected.add("VEVENT #" + draft.number + ": " + String.join("; ", draft.errors));
        }
        // A UID is an indivisible import unit. Never keep a master after dropping an unsupported exception.
        Map<String, List<Draft>> groups = new LinkedHashMap<>();
        for (Draft draft : drafts) for (String uid : draft.uids) groups.computeIfAbsent(uid, k -> new ArrayList<>()).add(draft);
        for (List<Draft> group : groups.values()) {
            String issue = null; Event master = null; Set<String> anchors = new HashSet<>();
            for (Draft draft : group) {
                if (!draft.errors.isEmpty() || draft.parsed == null) issue = "Entire recurring UID series rejected: invalid or unsupported group member";
                else if (draft.parsed.recurrenceId == null) { if (master != null) issue = "Duplicate UID master is ambiguous"; master = draft.parsed; }
                else if (!anchors.add(draft.parsed.sourceIdentity())) issue = "Duplicate RECURRENCE-ID is ambiguous";
            }
            if (!anchors.isEmpty()) {
                if (master == null || !master.recurring()) issue = "Entire recurring UID series rejected: RECURRENCE-ID needs one recurring master";
                else for (Draft draft : group) if (draft.parsed != null && draft.parsed.recurrenceId != null) {
                    Event exception = draft.parsed;
                    if (exception.recurring() || exception.recurrenceAllDay != master.allDay
                            || !exception.recurrenceAllDay && !exception.recurrenceZone.equals("UTC") && !exception.recurrenceZone.equals(master.zone))
                        issue = "Entire recurring UID series rejected: incompatible RECURRENCE-ID anchor";
                }
            }
            long groupBytes = 0; int operations = 2;
            for (Draft draft : group) {
                for (Line line : draft.properties.values()) groupBytes += line.raw().getBytes(StandardCharsets.UTF_8).length + 64L;
                for (Line line : draft.attendees) groupBytes += line.raw().getBytes(StandardCharsets.UTF_8).length + 512L;
                operations += 1 + draft.attendees.size();
            }
            if (groupBytes > 384 * 1024 || operations > 400) issue = "Entire recurring UID series exceeds safe atomic byte/operation limit";
            if (group.size() > 128) issue = "Entire recurring UID series exceeds safe atomic import limit (128 rows)";
            if (issue != null && (group.size() > 1 || group.get(0).exception || group.get(0).errors.isEmpty())) for (Draft draft : group) draft.reject(issue);
        }
        events.clear(); rejected.clear();
        for (Draft draft : drafts) {
            if (draft.errors.isEmpty() && draft.parsed != null) events.add(draft.parsed);
            else rejected.add("VEVENT #" + draft.number + ": " + String.join("; ", draft.errors));
        }
        return new Document(events, rejected, total, true);
    }

    private static Event event(Draft draft, ZoneId floatingZone, boolean cancellation) throws Invalid {
        for (Line property : draft.properties.values()) {
            switch (property.name) {
                case "UID": textParameters(property, false); require(!unescape(property.value).trim().isEmpty(), "UID must not be empty"); break;
                case "SUMMARY": case "DESCRIPTION": case "LOCATION":
                    textParameters(property, true);
                    if (property.parameters.containsKey("LANGUAGE")) require(language(single(property, "LANGUAGE")), "Invalid LANGUAGE");
                    unescape(property.value); break;
                case "DTSTART": case "DTEND": moment(property, floatingZone, true); break;
                case "DURATION": case "RRULE": noParameters(property); break;
                case "RECURRENCE-ID": moment(property, floatingZone, true); break;
                case "EXRULE": throw new Invalid("Unsupported recurrence property " + property.name);
                case "DTSTAMP": case "LAST-MODIFIED": case "CREATED":
                    require(property.value.endsWith("Z"), property.name + " must be UTC"); moment(property, floatingZone); break;
                case "SEQUENCE": noParameters(property); require(property.value.matches("[0-9]+"), "Invalid SEQUENCE");
                    try { Integer.parseInt(property.value); } catch (NumberFormatException bad) { throw new Invalid("SEQUENCE is too large"); } break;
                case "CLASS": noParameters(property); require(set("PUBLIC", "PRIVATE", "CONFIDENTIAL").contains(property.value.toUpperCase(Locale.ROOT)), "Invalid CLASS"); break;
                case "TRANSP": noParameters(property); require(set("OPAQUE", "TRANSPARENT").contains(property.value.toUpperCase(Locale.ROOT)), "Invalid TRANSP (expected OPAQUE or TRANSPARENT)"); break;
                case "X-BLOCKZEIT-PROVIDER-DTEND":
                    noParameters(property);
                    try {
                        long oldEnd = Long.parseLong(property.value);
                        require(property.value.equals(Long.toString(oldEnd)) && oldEnd % 1000 == 0, "Invalid archived DTEND");
                        int year = Instant.ofEpochMilli(oldEnd).atZone(ZoneOffset.UTC).getYear();
                        require(year >= 1 && year <= 9999, "Archived DTEND outside supported years");
                    } catch (NumberFormatException bad) { throw new Invalid("Invalid archived DTEND"); }
                    break;
                case "X-BLOCKZEIT-PROVIDER-END-TIMEZONE": noParameters(property);
                    try { ZoneId.of(unescape(property.value)); } catch (DateTimeException bad) { throw new Invalid("Invalid archived end timezone"); } break;
                case "STATUS": noParameters(property); require(set("CONFIRMED", "TENTATIVE", "CANCELLED").contains(property.value.toUpperCase(Locale.ROOT)), "Invalid STATUS"); break;
                case "ORGANIZER": for(String parameter:property.parameters.keySet())require(set("CN","DIR","SENT-BY","LANGUAGE").contains(parameter)||parameter.startsWith("X-"),"Unsupported ORGANIZER parameter "+parameter);
                    address(property.value, false); break;
                case "X-ANDROID-AVAILABILITY": noParameters(property); require(property.value.equals("2"), "Invalid X-ANDROID-AVAILABILITY"); break;
                case "X-ANDROID-EVENT-COLOR": nativeNumber(property, Integer.MIN_VALUE, Integer.MAX_VALUE); break;
                case "X-ANDROID-GUESTS-CAN-MODIFY": case "X-ANDROID-GUESTS-CAN-INVITE-OTHERS": case "X-ANDROID-GUESTS-CAN-SEE-GUESTS": nativeNumber(property, 0, 1); break;
                case "URL": case "ATTACH": case "CATEGORIES": case "COMMENT": case "CONTACT": case "RESOURCES":
                case "GEO": case "PRIORITY": case "COLOR": case "IMAGE": case "CONFERENCE":
                case "STRUCTURED-DATA": case "STYLED-DESCRIPTION": break;
                default: require(property.name.startsWith("X-"), "Unsupported event property " + property.name);
            }
        }
        require(draft.properties.containsKey("UID"), "Missing UID");
        if(!draft.properties.containsKey("DTSTART") && draft.properties.containsKey("RECURRENCE-ID")
                && (cancellation || draft.properties.containsKey("STATUS") && draft.properties.get("STATUS").value.equalsIgnoreCase("CANCELLED"))) {
            Line anchor=draft.properties.get("RECURRENCE-ID");
            draft.properties.put("DTSTART",new Line("DTSTART",anchor.value,anchor.parameters,anchor.number,null));
        }
        require(draft.properties.containsKey("DTSTART"), "Missing DTSTART");
        require(!(draft.properties.containsKey("DTEND") && draft.properties.containsKey("DURATION")), "DTEND and DURATION are mutually exclusive");
        Moment start = moment(draft.properties.get("DTSTART"), floatingZone, true);
        Metadata metadata = metadata(draft, start, floatingZone, cancellation);
        long end;
        if (draft.properties.containsKey("DTEND")) {
            Moment finish = moment(draft.properties.get("DTEND"), floatingZone, true);
            require(start.date == finish.date, "DTEND must match DTSTART value type");
            metadata.endZone = finish.zone.getId();
            end = finish.millis;
        } else if (draft.properties.containsKey("DURATION")) end = duration(draft.properties.get("DURATION").value, start);
        else {
            if (!start.date || metadata.status == 2 && draft.exception) end = start.millis;
            else {
            LocalDate next = start.local.toLocalDate().plusDays(1);
            require(next.getYear() <= 9999, "Default DATE end year outside 0001..9999");
            end = next.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        }
        require(end >= start.millis && (!start.date || end > start.millis || metadata.status == 2 && draft.exception), "Event duration must be positive for active DATE (exclusive end), nonnegative for timed events");
        String rule = draft.properties.containsKey("RRULE") ? standardRule(draft.properties.get("RRULE").value, start) : "";
        String rdate = dates(draft.rdates, start, floatingZone);
        String exdate = dates(draft.exdates, start, floatingZone);
        if (rule.isEmpty() && !rdate.isEmpty()) {
            // Android only adds DTSTART during RRULE expansion. An RDATE-only set must
            // include it explicitly, otherwise the first RFC occurrence silently disappears.
            Set<String> includingStart = new TreeSet<>();
            Collections.addAll(includingStart, rdate.split(","));
            includingStart.add(TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(start.millis), ZoneOffset.UTC)) + "Z");
            rdate = String.join(",", includingStart);
        }
        require(draft.rdates.size() + draft.exdates.size() <= MAX_RECURRENCE_DATES, "Recurrence date property limit exceeds 1024");
        boolean recurring = !rule.isEmpty() || !rdate.isEmpty();
        require(recurring || exdate.isEmpty(), "EXDATE needs RRULE or RDATE recurrence");
        String providerDuration = "";
        if (recurring) {
            safeRecurringClocks(start, rule);
            Line sourceDuration = draft.properties.get("DURATION");
            if (!start.date && sourceDuration != null && !start.zone.getRules().isFixedOffset()) {
                Matcher parts = DURATION.matcher(sourceDuration.value); parts.matches();
                require((parts.group(1) == null || Long.parseLong(parts.group(1)) == 0)
                        && (parts.group(2) == null || Long.parseLong(parts.group(2)) == 0),
                        "Recurring nominal day/week DURATION in a DST zone cannot map faithfully; use DTEND or elapsed hours");
            }
            // Android's duration parser has signed-int components; never hand it overflowing seconds.
            require(start.date || (end - start.millis) / 1000L <= Integer.MAX_VALUE,
                    "Recurring elapsed DURATION exceeds Android seconds limit");
            providerDuration = start.date ? "P" + ((end - start.millis) / 86400000L) + "D"
                    : "PT" + ((end - start.millis) / 1000L) + "S";
        }
        Line archived = draft.properties.get("X-BLOCKZEIT-PROVIDER-DTEND");
        String archivedZone = text(draft, "X-BLOCKZEIT-PROVIDER-END-TIMEZONE");
        require(archived == null || recurring && draft.properties.containsKey("DURATION") && !draft.exception,
                "Archived DTEND requires a duration-based recurrence master");
        require(archivedZone.isEmpty() || archived != null && !start.date, "Archived end timezone requires timed archived DTEND");
        if (archived != null && start.date) require(Long.parseLong(archived.value) % 86400000L == 0, "Archived all-day DTEND must be midnight UTC");
        int availability = draft.properties.containsKey("TRANSP") && draft.properties.get("TRANSP").value.equalsIgnoreCase("TRANSPARENT") ? 1 : 0;
        if (draft.properties.containsKey("X-ANDROID-AVAILABILITY")) {
            require(availability == 0, "Tentative availability contradicts TRANSP:TRANSPARENT"); availability = 2;
        }
        return new Event(unescape(draft.properties.get("UID").value), text(draft, "SUMMARY"),
                text(draft, "DESCRIPTION"), text(draft, "LOCATION"), start, end, rule, rdate, exdate, providerDuration, availability, archived == null ? null : Long.valueOf(archived.value), archivedZone, metadata);
    }

    /** Provider columns are deliberately represented without Android dependencies. */
    public static final class Attendee {
        public final String name, email, identity, namespace;
        public final int type, status, relationship;
        private Attendee(String name, String email, String identity, String namespace, int type, int status, int relationship) {
            this.name=name; this.email=email; this.identity=identity; this.namespace=namespace;
            this.type=type; this.status=status; this.relationship=relationship;
        }
        public String canonical() {
            StringBuilder out=new StringBuilder();
            for(String value:new String[]{name,email,identity,namespace,""+type,""+status,""+relationship}) out.append(value.length()).append(':').append(value);
            return out.toString();
        }
    }
    private static final class Metadata {
        String endZone, organizer="";
        int status=1, access=3;
        Moment original;
        final Map<String,Integer> nativeProperties=new TreeMap<>();
        final List<Attendee> attendees=new ArrayList<>();
    }
    private static Metadata metadata(Draft draft, Moment start, ZoneId floatingZone, boolean cancellation) throws Invalid {
        Metadata value=new Metadata(); value.endZone=start.zone.getId();
        // Absent status/access stay unspecified/default; never invent CONFIRMED or PUBLIC metadata.
        {
            value.access=0; value.status=-1;
            value.nativeProperties.put("X-ANDROID-GUESTS-CAN-MODIFY",0);
            value.nativeProperties.put("X-ANDROID-GUESTS-CAN-INVITE-OTHERS",1);
            value.nativeProperties.put("X-ANDROID-GUESTS-CAN-SEE-GUESTS",1);
        }
        Line property=draft.properties.get("STATUS");
        if(property!=null) value.status=property.value.equalsIgnoreCase("CANCELLED")?2:property.value.equalsIgnoreCase("TENTATIVE")?0:1;
        if(cancellation) { require(property == null || value.status == 2,"METHOD:CANCEL contradicts event STATUS"); value.status=2; }
        property=draft.properties.get("CLASS");
        if(property!=null) value.access=property.value.equalsIgnoreCase("CONFIDENTIAL")?1:property.value.equalsIgnoreCase("PRIVATE")?2:3;
        property=draft.properties.get("RECURRENCE-ID"); if(property!=null) value.original=moment(property,floatingZone,true);
        property=draft.properties.get("ORGANIZER"); if(property!=null && property.value.regionMatches(true,0,"mailto:",0,7)) value.organizer=address(property.value,true);
        for(String key:new String[]{"X-ANDROID-EVENT-COLOR","X-ANDROID-GUESTS-CAN-MODIFY","X-ANDROID-GUESTS-CAN-INVITE-OTHERS","X-ANDROID-GUESTS-CAN-SEE-GUESTS"}) {
            property=draft.properties.get(key); if(property!=null) value.nativeProperties.put(key,Integer.parseInt(property.value));
        }
        for(Line line:draft.attendees) { Attendee attendee=attendee(line); if(attendee!=null)value.attendees.add(attendee); }
        value.attendees.sort((a,b)->a.canonical().compareTo(b.canonical()));
        return value;
    }
    private static void nativeNumber(Line property, int min, int max) throws Invalid {
        noParameters(property);
        try { int n=Integer.parseInt(property.value); require(n>=min&&n<=max&&property.value.equals(Integer.toString(n)),"Invalid "+property.name); }
        catch(NumberFormatException bad){throw new Invalid("Invalid "+property.name);}
    }
    private static String address(String raw, boolean mailOnly) throws Invalid {
        try {
            URI uri=new URI(raw);
            require(uri.isAbsolute() && !raw.contains("\r") && !raw.contains("\n"),"Invalid calendar address");
            boolean mail=uri.getScheme().equalsIgnoreCase("mailto");
            require(!mailOnly||mail,"Only mailto organizer addresses can map to the provider");
            String address=mail?uri.getSchemeSpecificPart():uri.toASCIIString();
            require(!address.isEmpty()&&!address.contains("\r")&&!address.contains("\n")&&address.chars().allMatch(c->c>=32&&c!=127),"Invalid calendar address");
            return address;
        } catch(URISyntaxException bad){throw new Invalid("Invalid calendar address");}
    }
    private static String parameter(Line line,String key,String fallback) throws Invalid { return line.parameters.containsKey(key)?single(line,key):fallback; }
    private static Attendee attendee(Line line) throws Invalid {
        for(String key:line.parameters.keySet()) require(set("CN","ROLE","CUTYPE","PARTSTAT","RSVP","MEMBER","DELEGATED-TO","DELEGATED-FROM","DIR","LANGUAGE","X-ANDROID-RELATIONSHIP","X-ANDROID-IDENTITY","X-ANDROID-ID-NAMESPACE").contains(key) || key.startsWith("X-"),"Unsupported ATTENDEE parameter "+key);
        String role=parameter(line,"ROLE","REQ-PARTICIPANT").toUpperCase(Locale.ROOT),kind=parameter(line,"CUTYPE","INDIVIDUAL").toUpperCase(Locale.ROOT);
        if(!set("REQ-PARTICIPANT","OPT-PARTICIPANT","NON-PARTICIPANT").contains(role)) { address(line.value,false); return null; }
        if(!set("INDIVIDUAL","RESOURCE").contains(kind)) { address(line.value,false); return null; }
        require(!kind.equals("RESOURCE")||role.equals("NON-PARTICIPANT"),"ATTENDEE resource role is inconsistent");
        if(role.equals("NON-PARTICIPANT")&&!kind.equals("RESOURCE")) { address(line.value,false); return null; }
        int type=kind.equals("RESOURCE")?3:role.equals("OPT-PARTICIPANT")?2:1;
        String part=parameter(line,"PARTSTAT","NEEDS-ACTION").toUpperCase(Locale.ROOT);
        if(!set("NEEDS-ACTION","ACCEPTED","DECLINED","TENTATIVE").contains(part)) { address(line.value,false); return null; }
        int status=part.equals("ACCEPTED")?1:part.equals("DECLINED")?2:part.equals("TENTATIVE")?4:0;
        String relation=parameter(line,"X-ANDROID-RELATIONSHIP","0"); require(relation.matches("[0-4]"),"Invalid ATTENDEE relationship");
        String identity=parameter(line,"X-ANDROID-IDENTITY",""),namespace=parameter(line,"X-ANDROID-ID-NAMESPACE","");
        String address=address(line.value,false); boolean mail=line.value.regionMatches(true,0,"mailto:",0,7);
        if(!mail) { require(identity.isEmpty()||address(identity,false).equals(address),"ATTENDEE identity contradicts address"); if(identity.isEmpty())identity=address; }
        return new Attendee(parameter(line,"CN",""),mail?address:"",identity,namespace,type,status,Integer.parseInt(relation));
    }

    /** Origin-independent RFC syntax validation. The provider receives only validated selectors;
     * proven Android expansion mismatches are rejected rather than flattened or guessed. */
    private static String standardRule(String raw, Moment start) throws Invalid {
        Map<String,String> parts=new TreeMap<>();
        for(String field:raw.toUpperCase(Locale.ROOT).split(";",-1)) {
            String[] pair=field.split("=",-1); require(pair.length==2&&!pair[1].isEmpty(),"Malformed RRULE part");
            require(parts.put(pair[0],pair[1])==null,"Duplicate RRULE part "+pair[0]);
        }
        String freq=parts.get("FREQ");
        require(set("SECONDLY","MINUTELY","HOURLY","DAILY","WEEKLY","MONTHLY","YEARLY").contains(freq),"Unsupported RRULE FREQ");
        require(!(parts.containsKey("COUNT")&&parts.containsKey("UNTIL")),"RRULE COUNT and UNTIL are mutually exclusive");
        for(Map.Entry<String,String> part:parts.entrySet()) {
            String key=part.getKey(),value=part.getValue();
            switch(key) {
                case "FREQ": break;
                case "COUNT": case "INTERVAL": positive(value,Integer.MAX_VALUE,key); break;
                case "UNTIL":
                    require(start.date?value.matches("[0-9]{8}"):start.kind.equals("FLOATING")?value.matches("[0-9]{8}T[0-9]{6}"):value.matches("[0-9]{8}T[0-9]{6}Z"),start.date?"DATE RRULE UNTIL must be DATE":start.kind.equals("FLOATING")?"Floating RRULE UNTIL must be local DATE-TIME":"Zoned/UTC RRULE UNTIL must be UTC DATE-TIME");
                    Moment until=moment(new Line("UNTIL",value,start.date?Collections.singletonMap("VALUE",Collections.singletonList("DATE")):Collections.emptyMap(),0,null),start.zone);
                    require(until.millis>=start.millis,"RRULE UNTIL precedes DTSTART");
                    break;
                case "WKST": require(WEEKDAYS.contains(value),"Invalid RRULE WKST"); break;
                case "BYDAY": for(String day:value.split(",",-1)) {
                    Matcher match=Pattern.compile("([+-]?[0-9]{1,2})?(MO|TU|WE|TH|FR|SA|SU)").matcher(day); require(match.matches(),"Invalid RRULE BYDAY");
                    if(match.group(1)!=null) {int ordinal=Integer.parseInt(match.group(1));require(ordinal!=0&&ordinal>=-53&&ordinal<=53&&(freq.equals("MONTHLY")||freq.equals("YEARLY"))&&!parts.containsKey("BYWEEKNO"),"Invalid ordinal RRULE BYDAY");}
                } break;
                case "BYSECOND": numbers(parts,key,0,60,false); break;
                case "BYMINUTE": numbers(parts,key,0,59,false); break;
                case "BYHOUR": numbers(parts,key,0,23,false); break;
                case "BYMONTHDAY": require(!freq.equals("WEEKLY"),"WEEKLY BYMONTHDAY is forbidden"); numbers(parts,key,-31,31,true); break;
                case "BYYEARDAY": require(!set("DAILY","WEEKLY","MONTHLY").contains(freq),"Invalid BYYEARDAY frequency"); nativeNumbers(value,-366,366,true); break;
                case "BYWEEKNO": require(freq.equals("YEARLY"),"BYWEEKNO needs YEARLY"); numbers(parts,key,-53,53,true); break;
                case "BYMONTH": numbers(parts,key,1,12,false); break;
                case "BYSETPOS": require(parts.keySet().stream().anyMatch(k->k.startsWith("BY")&&!k.equals("BYSETPOS")),"BYSETPOS needs a BY selector"); nativeNumbers(value,-366,366,true); break;
                default: throw new Invalid("Unsupported RRULE part "+key);
            }
        }
        // RFC 5545: BYHOUR/BYMINUTE/BYSECOND MUST be ignored with DATE DTSTART.
        if(start.date) { parts.remove("BYHOUR");parts.remove("BYMINUTE");parts.remove("BYSECOND"); }
        if(parts.containsKey("BYMONTH"))require(set(parts.get("BYMONTH").split(",")).contains(""+start.local.getMonthValue()),"DTSTART does not match RRULE BYMONTH");
        if(parts.containsKey("BYMONTHDAY")) {
            Set<String> selected=set(parts.get("BYMONTHDAY").split(","));
            require(selected.contains(""+start.local.getDayOfMonth())||selected.contains(""+(start.local.getDayOfMonth()-start.local.toLocalDate().lengthOfMonth()-1)),"DTSTART does not match RRULE BYMONTHDAY");
        }
        if(parts.containsKey("BYDAY")) {
            boolean matches=false;
            for(String selected:parts.get("BYDAY").split(",")) {
                String day=selected.substring(selected.length()-2),ordinal=selected.substring(0,selected.length()-2);
                if(day.equals(weekday(start.local.toLocalDate()))) {
                    int positive=(start.local.getDayOfMonth()-1)/7+1,negative=-((start.local.toLocalDate().lengthOfMonth()-start.local.getDayOfMonth())/7+1);
                    if(ordinal.isEmpty()||Integer.parseInt(ordinal)==positive||Integer.parseInt(ordinal)==negative)matches=true;
                }
            }
            require(matches,"DTSTART does not match RRULE BYDAY");
        }
        require(!parts.containsKey("BYWEEKNO") && !parts.containsKey("BYYEARDAY"),"BYWEEKNO/BYYEARDAY cannot map faithfully to Android");
        // The checked AOSP filter unconditionally filters subdaily rules through missing
        // BYHOUR/BYMINUTE/BYSECOND lists (and mishandles zero list values). Even bounded
        // UTC examples silently lose occurrences. Do not advertise syntax-only support.
        require(!set("SECONDLY","MINUTELY","HOURLY").contains(freq),"Subdaily recurrence cannot map faithfully to Android (SECONDLY/MINUTELY/HOURLY)");
        if(parts.containsKey("BYSETPOS")) {
            require(freq.equals("MONTHLY") && parts.containsKey("BYDAY") && !parts.containsKey("BYMONTHDAY")
                    && parts.get("BYDAY").matches("[A-Z,]+"),"Only monthly nonordinal weekday BYSETPOS maps faithfully to Android");
            for(String key:new String[]{"BYHOUR","BYMINUTE","BYSECOND"})require(!parts.containsKey(key)||parts.get(key).split(",").length==1,"BYSETPOS with multiple daily times cannot map faithfully to Android");
            Set<String> weekdays=set(parts.get("BYDAY").split(","));List<Integer> days=new ArrayList<>();
            for(int day=1;day<=start.local.toLocalDate().lengthOfMonth();day++)if(weekdays.contains(weekday(start.local.toLocalDate().withDayOfMonth(day))))days.add(day);
            boolean matches=false;
            for(String position:parts.get("BYSETPOS").split(",")){int n=Integer.parseInt(position),index=n>0?n-1:days.size()+n;if(index>=0&&index<days.size()&&days.get(index)==start.local.getDayOfMonth())matches=true;}
            require(matches,"DTSTART does not match RRULE BYSETPOS");
        }
        if(set("SECONDLY","MINUTELY","HOURLY","DAILY").contains(freq) && parts.containsKey("BYMONTHDAY"))
            for(String day:parts.get("BYMONTHDAY").split(","))require(Integer.parseInt(day)>0,"Negative BYMONTHDAY filtering cannot map faithfully to Android");
        for(String key:new String[]{"BYHOUR","BYMINUTE","BYSECOND"})if(parts.containsKey(key)) {
            int clock=key.equals("BYHOUR")?start.local.getHour():key.equals("BYMINUTE")?start.local.getMinute():start.local.getSecond();
            boolean matches=false;for(String n:parts.get(key).split(","))if(Integer.parseInt(n)==clock)matches=true;
            require(matches,"DTSTART does not match RRULE "+key);
        }
        if(freq.equals("YEARLY")) {
            int months=parts.containsKey("BYMONTH")?parts.get("BYMONTH").split(",").length:0;
            if(start.local.getMonthValue()==2&&start.local.getDayOfMonth()==29)require(!parts.containsKey("BYDAY")
                    && (!parts.containsKey("BYMONTH")||parts.get("BYMONTH").equals("2"))
                    && (!parts.containsKey("BYMONTHDAY")||parts.get("BYMONTHDAY").equals("29")),"YEARLY leap-day selectors cannot map faithfully to Android");
            require(!parts.containsKey("BYMONTHDAY") || months == 1,"YEARLY BYMONTHDAY needs exactly one BYMONTH for faithful Android expansion");
            require(!parts.containsKey("BYDAY") || months == 1 || parts.containsKey("BYWEEKNO"),"YEARLY BYDAY needs exactly one BYMONTH for faithful Android expansion");
        }
        require(!parts.containsKey("BYDAY") || !parts.containsKey("BYMONTHDAY"),"Combined BYDAY and BYMONTHDAY cannot map faithfully to Android");
        if(set("DAILY","WEEKLY","MONTHLY","YEARLY").contains(freq)
                && !parts.containsKey("BYSETPOS") && !parts.containsKey("BYHOUR") && !parts.containsKey("BYMINUTE")
                && !parts.containsKey("BYSECOND") && !parts.containsKey("BYYEARDAY") && !parts.containsKey("BYWEEKNO")) return rule(canonicalRule(parts),start);
        require(!parts.containsKey("BYSECOND") || !parts.get("BYSECOND").matches("(?:.*,)?60(?:,.*)?"),"Leap-second recurrence cannot map faithfully to Android");
        if(parts.containsKey("UNTIL")&&!start.date) {
            Moment until=moment(new Line("UNTIL",parts.get("UNTIL"),Collections.emptyMap(),0,null),start.zone);
            parts.put("UNTIL",TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(until.millis),ZoneOffset.UTC))+"Z");
        }
        StringBuilder canonical=new StringBuilder("FREQ="+freq);parts.remove("FREQ");
        for(Map.Entry<String,String> part:parts.entrySet())canonical.append(';').append(part.getKey()).append('=').append(part.getValue());
        return canonical.toString();
    }
    private static String canonicalRule(Map<String,String> parts) {
        List<String> fields=new ArrayList<>();for(Map.Entry<String,String> part:parts.entrySet())fields.add(part.getKey()+"="+part.getValue());return String.join(";",fields);
    }
    private static void nativeNumbers(String value,int min,int max,boolean noZero) throws Invalid {
        for(String raw:value.split(",",-1)) {
            require(raw.matches("[+-]?[0-9]{1,3}"),"Invalid native recurrence selector"); int n=Integer.parseInt(raw);
            require(n>=min&&n<=max&&(!noZero||n!=0),"Native recurrence selector outside supported range");
        }
    }

    /** Android receives a rule, never a materialized list of occurrences. */
    private static String rule(String raw, Moment start) throws Invalid {
        Map<String, String> parts = new TreeMap<>();
        for (String item : raw.toUpperCase(Locale.ROOT).split(";", -1)) {
            String[] pair = item.split("=", -1);
            require(pair.length == 2 && !pair[1].isEmpty(), "Malformed RRULE part");
            require(set("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST").contains(pair[0]),
                    "Unsupported RRULE part " + pair[0]);
            require(parts.put(pair[0], pair[1]) == null, "Duplicate RRULE part " + pair[0]);
        }
        String freq = parts.get("FREQ");
        require(set("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(freq), "Unsupported RRULE FREQ");
        require(!(parts.containsKey("COUNT") && parts.containsKey("UNTIL")), "RRULE COUNT and UNTIL are mutually exclusive");
        if (parts.containsKey("INTERVAL")) {
            int interval = positive(parts.get("INTERVAL"), 1000, "INTERVAL");
            if (interval == 1) parts.remove("INTERVAL"); else parts.put("INTERVAL", "" + interval);
        }
        if (parts.containsKey("COUNT")) parts.put("COUNT", "" + positive(parts.get("COUNT"), 1000000, "COUNT"));
        if (parts.containsKey("UNTIL")) {
            String until = parts.get("UNTIL");
            if (start.date) require(until.matches("[0-9]{8}"), "DATE RRULE UNTIL must be DATE");
            else if (start.kind.equals("FLOATING")) require(until.matches("[0-9]{8}T[0-9]{6}"), "Floating RRULE UNTIL must be local DATE-TIME");
            else require(until.matches("[0-9]{8}T[0-9]{6}Z"), "Zoned/UTC RRULE UNTIL must be UTC DATE-TIME");
            Map<String, List<String>> parameters = start.date
                    ? Collections.singletonMap("VALUE", Collections.singletonList("DATE")) : Collections.emptyMap();
            Moment finish = moment(new Line("UNTIL", until, parameters, 0, null), start.zone);
            require(finish.millis >= start.millis, "RRULE UNTIL precedes DTSTART");
            if (!start.date) parts.put("UNTIL", TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(finish.millis), ZoneOffset.UTC)) + "Z");
        }
        if (parts.containsKey("WKST")) {
            require(WEEKDAYS.contains(parts.get("WKST")), "Invalid RRULE WKST");
            if (!freq.equals("WEEKLY") || parts.get("WKST").equals("MO")) parts.remove("WKST");
        }
        Set<Integer> months = numbers(parts, "BYMONTH", 1, 12, false);
        Set<Integer> monthdays = numbers(parts, "BYMONTHDAY", -31, 31, true);
        require(!freq.equals("WEEKLY") || monthdays.isEmpty(), "WEEKLY BYMONTHDAY is forbidden");
        // Android's yearly day-set is computed once against the iterator's current month.
        // Multiple/implicit months can lose occurrences or reuse the wrong weekday/day numbers.
        require(!freq.equals("YEARLY") || monthdays.isEmpty() || months.size() == 1,
                "YEARLY BYMONTHDAY needs exactly one BYMONTH for faithful Android expansion");
        if (!months.isEmpty()) require(months.contains(start.local.getMonthValue()), "DTSTART does not match RRULE BYMONTH");
        if (!monthdays.isEmpty()) require(monthdays.contains(start.local.getDayOfMonth())
                || monthdays.contains(start.local.getDayOfMonth() - start.local.toLocalDate().lengthOfMonth() - 1),
                "DTSTART does not match RRULE BYMONTHDAY");
        if (parts.containsKey("BYDAY")) {
            Set<String> days = new TreeSet<>();
            boolean matches = false, ordinal = false;
            for (String day : parts.get("BYDAY").split(",", -1)) {
                Matcher match = Pattern.compile("([+-]?[1-5])?(MO|TU|WE|TH|FR|SA|SU)").matcher(day);
                require(match.matches(), "Invalid/unsupported RRULE BYDAY");
                String weekday = match.group(2);
                int number = match.group(1) == null ? 0 : Integer.parseInt(match.group(1));
                ordinal |= number != 0;
                require(number == 0 || freq.equals("MONTHLY") || freq.equals("YEARLY"), "Ordinal BYDAY needs MONTHLY/YEARLY");
                String normalized = (number == 0 ? "" : "" + number) + weekday;
                days.add(normalized);
                if (weekday(start.local.toLocalDate()).equals(weekday)) {
                    int positive = (start.local.getDayOfMonth() - 1) / 7 + 1;
                    int negative = -((start.local.toLocalDate().lengthOfMonth() - start.local.getDayOfMonth()) / 7 + 1);
                    if (number == 0 || number == positive || number == negative) matches = true;
                }
            }
            require(!ordinal || !freq.equals("YEARLY") || !months.isEmpty(), "YEARLY ordinal BYDAY needs BYMONTH");
            require(monthdays.isEmpty(), "Combined BYDAY and BYMONTHDAY is unsupported");
            require(matches, "DTSTART does not match RRULE BYDAY");
            parts.put("BYDAY", String.join(",", days));
        }
        require(!freq.equals("YEARLY") || !parts.containsKey("BYDAY") || months.size() == 1,
                "YEARLY BYDAY needs exactly one BYMONTH for faithful Android expansion");
        if (freq.equals("YEARLY") && start.local.getMonthValue() == 2 && start.local.getDayOfMonth() == 29) {
            // AOSP keeps the iterator's original day while stepping years. A Feb-29
            // master skips non-leap years even when selectors require February 28, etc.
            require(!parts.containsKey("BYDAY") && (months.isEmpty() || months.equals(Collections.singleton(2)))
                            && (monthdays.isEmpty() || monthdays.equals(Collections.singleton(29))),
                    "YEARLY leap-day DTSTART with expanded day/month selectors cannot map faithfully to Android");
        }
        StringBuilder canonical = new StringBuilder("FREQ=" + freq);
        parts.remove("FREQ");
        for (Map.Entry<String, String> part : parts.entrySet()) canonical.append(';').append(part.getKey()).append('=').append(part.getValue());
        return canonical.toString();
    }

    private static int positive(String value, int max, String name) throws Invalid {
        require(value.matches("[0-9]{1,10}"), "Invalid RRULE " + name);
        long number = Long.parseLong(value);
        require(number >= 1 && number <= max, "RRULE " + name + " outside 1.." + max);
        return (int) number;
    }

    private static Set<Integer> numbers(Map<String, String> parts, String key, int min, int max, boolean noZero) throws Invalid {
        Set<Integer> result = new TreeSet<>();
        if (!parts.containsKey(key)) return result;
        for (String raw : parts.get(key).split(",", -1)) {
            require(raw.matches("[+-]?[0-9]{1,2}"), "Invalid RRULE " + key);
            int number = Integer.parseInt(raw);
            require(number >= min && number <= max && (!noZero || number != 0), "Invalid RRULE " + key);
            result.add(number);
        }
        List<String> values = new ArrayList<>(); for (int value : result) values.add("" + value);
        parts.put(key, String.join(",", values)); return result;
    }

    private static String weekday(LocalDate date) { return new String[]{"MO", "TU", "WE", "TH", "FR", "SA", "SU"}[date.getDayOfWeek().getValue() - 1]; }

    /** Normalize source-local/UTC lists to UTC provider instants; all-day dates stay UTC date labels. */
    private static String dates(List<Line> lines, Moment start, ZoneId floatingZone) throws Invalid {
        Set<Long> values = new TreeSet<>(); int count = 0;
        for (Line line : lines) {
            for (String raw : line.value.split(",", -1)) {
                require(++count <= MAX_RECURRENCE_DATES, "Recurrence date value limit exceeds 1024");
                Moment date = moment(new Line(line.name, raw, line.parameters, line.number, null), floatingZone);
                require(date.date == start.date, line.name + " must match DTSTART value type");
                if (!date.date) require(date.kind.equals("UTC") || date.kind.equals(start.kind) && date.zone.equals(start.zone),
                        line.name + " local timezone must match DTSTART (or use UTC)");
                values.add(date.millis);
            }
        }
        List<String> normalized = new ArrayList<>();
        for (long value : values) normalized.add(TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)) + "Z");
        return String.join(",", normalized);
    }

    private static void safeRecurringClocks(Moment start,String rule) throws Invalid {
        if(start.date || start.zone.getRules().isFixedOffset())return;
        Map<String,String> parts=new HashMap<>();for(String field:rule.split(";")){String[] pair=field.split("=",2);if(pair.length==2)parts.put(pair[0],pair[1]);}
        String[] hours=parts.getOrDefault("BYHOUR",""+start.local.getHour()).split(","),minutes=parts.getOrDefault("BYMINUTE",""+start.local.getMinute()).split(","),seconds=parts.getOrDefault("BYSECOND",""+start.local.getSecond()).split(",");
        require((long)hours.length*minutes.length*seconds.length<=64,"Recurrence clock selector combination limit exceeds 64");
        for(String hour:hours)for(String minute:minutes)for(String second:seconds) {
            LocalDateTime clock=start.local.withHour(Integer.parseInt(hour)).withMinute(Integer.parseInt(minute)).withSecond(Integer.parseInt(second));
            safeRecurringClock(new Moment(false,start.zone,clock,start.millis,start.kind));
        }
    }
    /** Android normalizes nonexistent recurrence wall times. Do not delegate such ambiguous series. */
    private static void safeRecurringClock(Moment start) throws Invalid {
        if (start.date || start.zone.getRules().isFixedOffset()) return;
        // Future rules repeat on the Gregorian 400-year cycle; historic transitions are finite.
        for (ZoneOffsetTransition transition : start.zone.getRules().getTransitions()) {
            if (transition.getInstant().toEpochMilli() >= start.millis) safeClock(start, transition);
        }
        int first = Math.max(start.local.getYear(), 2000);
        int last = Math.min(9999, first + 400);
        for (int year = first; year <= last; year++)
            for (java.time.zone.ZoneOffsetTransitionRule transition : start.zone.getRules().getTransitionRules())
                safeClock(start, transition.createTransition(year));
    }

    private static void safeClock(Moment start, ZoneOffsetTransition transition) throws Invalid {
        LocalDateTime lower = transition.isGap() ? transition.getDateTimeBefore() : transition.getDateTimeAfter();
        LocalDateTime upper = transition.isGap() ? transition.getDateTimeAfter() : transition.getDateTimeBefore();
        LocalDateTime candidate = lower.toLocalDate().atTime(start.local.toLocalTime());
        require(candidate.isBefore(lower) || !candidate.isBefore(upper), "Recurring DTSTART clock can enter a DST gap/overlap; unsupported");
    }

    private static long duration(String value, Moment start) throws Invalid {
        Matcher match = DURATION.matcher(value);
        require(match.matches(), "Unsupported or invalid DURATION");
        boolean any = false;
        long[] numbers = new long[5];
        for (int i = 0; i < numbers.length; i++) {
            if (match.group(i + 1) != null) {
                any = true;
                try { numbers[i] = Long.parseLong(match.group(i + 1)); }
                catch (NumberFormatException large) { throw new Invalid("DURATION overflow"); }
            }
        }
        require(any && !value.endsWith("T"), "DURATION must contain a positive amount");
        long days = Math.addExact(Math.multiplyExact(numbers[0], 7L), numbers[1]);
        long seconds = Math.addExact(Math.addExact(Math.multiplyExact(numbers[2], 3600L), Math.multiplyExact(numbers[3], 60L)), numbers[4]);
        require(!start.date || days > 0 || seconds > 0, "DATE DURATION must be positive");
        require(!start.date || value.indexOf('T') < 0, "DATE DURATION must use whole days or weeks");
        // RFC duration semantics: nominal weeks/days first, then elapsed hours/minutes/seconds.
        LocalDateTime nominal = start.local.plusDays(days);
        long base = days == 0 ? start.millis : strict(nominal, start.zone).toEpochMilli();
        Instant finish = Instant.ofEpochMilli(base).plusSeconds(seconds);
        LocalDateTime localEnd = LocalDateTime.ofInstant(finish, start.zone);
        require(localEnd.getYear() >= 1 && localEnd.getYear() <= 9999, "DURATION end year outside 0001..9999");

        return finish.toEpochMilli();
    }

    private static String sourceZone(Line line,ZoneId floatingZone) throws Invalid {
        if(line==null)return floatingZone.getId();
        if(line.parameters.containsKey("TZID"))return single(line,"TZID");
        if(line.value.endsWith("Z") || line.parameters.containsKey("VALUE")&&single(line,"VALUE").equalsIgnoreCase("DATE"))return "UTC";
        return floatingZone.getId();
    }
    private static ZoneId nativeZone(ZoneId zone) throws Invalid {
        if(!zone.getRules().isFixedOffset())return zone;
        ZoneOffset offset=zone.getRules().getOffset(Instant.EPOCH);
        require(offset.getTotalSeconds()%60==0,"Fixed timezone offset seconds cannot map faithfully to Android");
        java.util.TimeZone nativeZone=java.util.TimeZone.getTimeZone(zone.getId());
        if(offset.getTotalSeconds()!=0 && nativeZone.getRawOffset()!=offset.getTotalSeconds()*1000)
            return ZoneId.of("GMT"+offset.getId());
        return zone;
    }
    private static Moment moment(Line line, ZoneId floatingZone) throws Invalid { return moment(line, floatingZone, false); }
    private static Moment moment(Line line, ZoneId floatingZone, boolean firstFold) throws Invalid {
        for (String parameter : line.parameters.keySet()) require(parameter.equals("VALUE") || parameter.equals("TZID"), "Unsupported " + line.name + " parameter " + parameter);
        String type = line.parameters.containsKey("VALUE") ? single(line, "VALUE").toUpperCase(Locale.ROOT) : "DATE-TIME";
        boolean date = type.equals("DATE");
        require(date || type.equals("DATE-TIME"), "Unsupported datetime VALUE " + type);
        String timezone = line.parameters.containsKey("TZID") ? single(line, "TZID") : null;
        String raw = line.value;
        boolean utc = raw.endsWith("Z");
        require(!date || timezone == null, "TZID is forbidden on DATE");
        require(!utc || timezone == null, "TZID is forbidden on UTC DATE-TIME");
        ZoneId zone = date || utc ? ZoneId.of("UTC") : floatingZone;
        if (timezone != null) {
            try { zone = ZoneId.of(timezone); }
            catch (DateTimeException bad) { throw new Invalid("Unknown/non-IANA TZID " + timezone); }
            require(ZONES.contains(timezone) || zone.getRules().isFixedOffset(), "Unknown/non-IANA TZID " + timezone);
        }
        zone = nativeZone(zone);
        LocalDateTime local;
        if (date) {
            require(raw.matches("[0-9]{8}"), "DATE must be YYYYMMDD");
            local = LocalDate.parse(raw, DATE).atStartOfDay();
        } else {
            require(raw.matches("[0-9]{8}T[0-9]{6}Z?"), "DATE-TIME must include seconds, with optional UTC Z");
            local = LocalDateTime.parse(utc ? raw.substring(0, raw.length() - 1) : raw, TIME);
        }
        require(local.getYear() >= 1 && local.getYear() <= 9999, "Date year outside 0001..9999");
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        require(!offsets.isEmpty() && (firstFold || offsets.size() == 1), "DST gap or ambiguous local time in " + zone.getId());
        return new Moment(date, zone, local, local.toInstant(offsets.get(0)).toEpochMilli(), date ? "DATE" : utc ? "UTC" : timezone == null ? "FLOATING" : "TZID");
    }

    private static Instant strict(LocalDateTime local, ZoneId zone) throws Invalid {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        require(offsets.size() == 1, "DST gap or ambiguous local time in " + zone.getId());
        return local.toInstant(offsets.get(0));
    }

    private static Line logicalLine(ByteArrayOutputStream bytes, int number) throws Invalid {
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) { throw new Invalid("Invalid UTF-8 at line " + number); }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c != '\r' && (c >= 32 || c == '\t') && c != 127, "Invalid control character at line " + number);
        }
        return line(value, number);
    }

    private static Line line(String value, int number) {
        String guessed = value.split("[;:]", 2)[0].toUpperCase(Locale.ROOT);
        try {
            int colon = outside(value, ':');
            require(colon > 0, "Missing property colon at line " + number);
            List<String> head = split(value.substring(0, colon), ';');
            String name = head.get(0).toUpperCase(Locale.ROOT);
            require(name.matches("[A-Z0-9-]+"), "Unsupported/invalid property name at line " + number);
            Map<String, List<String>> parameters = new LinkedHashMap<>();
            for (int i = 1; i < head.size(); i++) {
                String parameter = head.get(i);
                int equals = parameter.indexOf('=');
                require(equals > 0, "Invalid parameter at line " + number);
                String key = parameter.substring(0, equals).toUpperCase(Locale.ROOT);
                require(key.matches("[A-Z0-9-]+"), "Invalid parameter name");
                List<String> values = new ArrayList<>();
                for (String item : split(parameter.substring(equals + 1), ',')) {
                    require(!item.isEmpty(), "Empty parameter value");
                    if (item.startsWith("\"")) {
                        require(item.length() >= 2 && item.endsWith("\""), "Invalid quoted parameter");
                        item = item.substring(1, item.length() - 1);
                        require(item.indexOf('"') < 0, "Invalid quote in parameter");
                    } else require(item.indexOf('"') < 0 && item.indexOf(':') < 0, "Parameter value needs quotes");
                    values.add(parameterText(item));
                }
                require(parameters.put(key, values) == null, "Duplicate parameter " + key);
            }
            return new Line(name, value.substring(colon + 1), parameters, number, null);
        } catch (Invalid invalid) { return new Line(guessed, "", Collections.emptyMap(), number, invalid.getMessage()); }
    }

    private static String parameterText(String raw) throws Invalid {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '^' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '^' || next == '\'') { out.append(next == '^' ? '^' : '"'); i++; continue; }
                if (next == 'n' || next == 'N') { out.append('\n'); i++; continue; }
            }
            out.append(c); // RFC 6868 leaves unrecognized caret sequences unchanged.
        }
        return out.toString();
    }

    private static int outside(String value, char separator) throws Invalid {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '"') quoted = !quoted;
            else if (!quoted && value.charAt(i) == separator) return i;
        }
        require(!quoted, "Unterminated quoted parameter");
        return -1;
    }

    private static List<String> split(String value, char separator) throws Invalid {
        List<String> result = new ArrayList<>();
        boolean quoted = false;
        int begin = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == separator) { result.add(value.substring(begin, i)); begin = i + 1; }
        }
        require(!quoted, "Unterminated quoted parameter");
        result.add(value.substring(begin));
        return result;
    }

    private static String unescape(String raw) throws Invalid {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                require(++i < raw.length(), "Trailing TEXT escape"); c = raw.charAt(i);
                if (c == 'n' || c == 'N') c = '\n';
                else require(c == '\\' || c == ',' || c == ';', "Unsupported TEXT escape");
            } else require(c != ',' && c != ';', "TEXT comma/semicolon must be escaped");
            value.append(c);
        }
        return value.toString();
    }

    private static boolean language(String value) {
        // Avoid a recursive repeated regex: an adversarial but bounded tag can exhaust the JVM stack.
        boolean first = true;
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-') {
                if (length == 0) return false;
                first = false; length = 0;
            } else {
                boolean letter = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
                if (!letter && (first || c < '0' || c > '9') || ++length > 8) return false;
            }
        }
        return length > 0;
    }

    private static String text(Draft draft, String name) throws Invalid {
        Line line = draft.properties.get(name); return line == null ? "" : unescape(line.value);
    }
    private static boolean optionalEventProperty(String name) {
        if(set("URL","ATTACH","CATEGORIES","COMMENT","CONTACT","RESOURCES","GEO","PRIORITY","COLOR","IMAGE","CONFERENCE","STRUCTURED-DATA","STYLED-DESCRIPTION").contains(name))return true;
        return name.startsWith("X-")&&!set("X-ANDROID-AVAILABILITY","X-ANDROID-EVENT-COLOR","X-ANDROID-GUESTS-CAN-MODIFY","X-ANDROID-GUESTS-CAN-INVITE-OTHERS","X-ANDROID-GUESTS-CAN-SEE-GUESTS","X-BLOCKZEIT-PROVIDER-DTEND","X-BLOCKZEIT-PROVIDER-END-TIMEZONE").contains(name);
    }
    private static void textParameters(Line line, boolean descriptive) throws Invalid {
        for(String key:line.parameters.keySet())require(key.equals("VALUE") || descriptive && set("LANGUAGE","ALTREP").contains(key),"Unsupported "+line.name+" parameter "+key);
        if(line.parameters.containsKey("VALUE"))require(single(line,"VALUE").equalsIgnoreCase("TEXT"),"Unsupported TEXT VALUE");
    }
    private static void noParameters(Line line) throws Invalid { require(line.parameters.isEmpty(), "Unsupported parameters on " + line.name); }
    private static String single(Line line, String name) throws Invalid {
        List<String> values = line.parameters.get(name);
        require(values.size() == 1, "Multiple " + name + " values are unsupported"); return values.get(0);
    }
    private static void require(boolean condition, String message) throws Invalid { if (!condition) throw new Invalid(message); }
    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("iCalendar parsing cancelled");
    }
    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>(); Collections.addAll(result, values); return Collections.unmodifiableSet(result);
    }
    private static final class Invalid extends Exception { Invalid(String message) { super(message); } }
    private static final class Moment {
        final boolean date; final ZoneId zone; final LocalDateTime local; final long millis; final String kind;
        Moment(boolean date, ZoneId zone, LocalDateTime local, long millis, String kind) {
            this.date = date; this.zone = zone; this.local = local; this.millis = millis; this.kind = kind;
        }
    }
    private static final class Line {
        final String name, value, error; final Map<String, List<String>> parameters; final int number;
        Line(String name, String value, Map<String, List<String>> parameters, int number, String error) {
            this.name = name; this.value = value; this.parameters = parameters; this.number = number; this.error = error;
        }
        String raw() {
            StringBuilder out = new StringBuilder(name);
            for (Map.Entry<String, List<String>> p : parameters.entrySet())
                out.append(';').append(p.getKey()).append('=')
                        .append(String.join(",", p.getValue()));
            return out.append(':').append(value).toString();
        }
        boolean is(String name, String value) { return error == null && this.name.equals(name) && this.value.equalsIgnoreCase(value); }
    }
    private static final class Draft {
        final int number; final Map<String, Line> properties = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>(); final Set<String> uids = new HashSet<>(); boolean exception;
        final List<Line> rdates = new ArrayList<>(), exdates = new ArrayList<>(), attendees = new ArrayList<>();
        Event parsed;
        Draft(int number) { this.number = number; }
        void reject(String reason) { if (errors.size() < 8 && !errors.contains(reason)) errors.add(reason); }
        void add(Line line) {
            if (line.name.equals("RECURRENCE-ID")) exception = true;
            if (line.error != null) { reject(line.error); return; }
            if (line.name.equals("UID")) {
                try { uids.add(unescape(line.value)); } catch (Invalid invalid) { reject(invalid.getMessage()); }
            }
            if (line.name.equals("ATTENDEE")) {
                if (attendees.size() < 1000) attendees.add(line); else reject("Attendee count exceeds per-event limit (1000)");
                return;
            }
            if (line.name.equals("RDATE") || line.name.equals("EXDATE")) {
                List<Line> list = line.name.equals("RDATE") ? rdates : exdates;
                if (list.size() < MAX_RECURRENCE_DATES) list.add(line);
                else reject("Recurrence date property limit exceeds 1024");
                return;
            }
            if (optionalEventProperty(line.name)) return;
            if (properties.putIfAbsent(line.name, line) != null) reject("Duplicate event property " + line.name);
        }
    }
}
