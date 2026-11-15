package de.blockzeit;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Attendees;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.zone.*;
import java.util.*;
import java.util.regex.*;

/** Stored masters + exceptions, never a bounded Instances expansion. No reminders/preferences.
 * A bounded complete snapshot is serialized twice before opening the SAF output; observed changes,
 * unsupported/inconsistent rows and empty calendars fail closed instead of creating partial exports.
 */
final class CalendarExport {
    static final int MAX_BYTES=2*1024*1024, MAX_EVENTS=2000, MAX_ATTENDEES=10000;
    static final String[] CAL={Calendars._ID,Calendars.NAME,Calendars.CALENDAR_DISPLAY_NAME,Calendars.CALENDAR_TIME_ZONE,
        "#"+Calendars.CALENDAR_COLOR};
    static final String[] EVENT={"#"+Events._ID,Events.UID_2445,Events._SYNC_ID,Events.ORIGINAL_SYNC_ID,
        Events.TITLE,Events.DESCRIPTION,Events.EVENT_LOCATION,"#"+Events.DTSTART,"#"+Events.DTEND,Events.DURATION,
        Events.EVENT_TIMEZONE,Events.EVENT_END_TIMEZONE,"#"+Events.ALL_DAY,"#"+Events.STATUS,"#"+Events.ACCESS_LEVEL,
        "#"+Events.AVAILABILITY,Events.RRULE,Events.RDATE,Events.EXRULE,Events.EXDATE,"#"+Events.ORIGINAL_ID,
        "#"+Events.ORIGINAL_INSTANCE_TIME,"#"+Events.ORIGINAL_ALL_DAY,Events.ORGANIZER,"#"+Events.EVENT_COLOR,
        "#"+Events.GUESTS_CAN_MODIFY,"#"+Events.GUESTS_CAN_INVITE_OTHERS,"#"+Events.GUESTS_CAN_SEE_GUESTS};
    static final String[] ATTENDEE={"#"+Attendees.EVENT_ID,Attendees.ATTENDEE_NAME,Attendees.ATTENDEE_EMAIL,
        "#"+Attendees.ATTENDEE_RELATIONSHIP,"#"+Attendees.ATTENDEE_TYPE,"#"+Attendees.ATTENDEE_STATUS,
        Attendees.ATTENDEE_IDENTITY,Attendees.ATTENDEE_ID_NAMESPACE};
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuuMMdd",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    // Transient capture evidence, never a provider column or output property.
    private static final String COERCED_DTEND="blockzeit:coerced-dtend";
    private CalendarExport(){}
    /** Closed codes/fields, a positive ID, and only for end diagnostics two normalized durations. */
    private enum Phase { CAPTURE_CALENDAR, CAPTURE_EVENT, CAPTURE_ATTENDEE, RELATION, ENCODE_CALENDAR, ENCODE_EVENT, TIMEZONE, SERIALIZE, COMPARE }
    private enum Reason {
        INVALID_VALUE("Unsupported or inconsistent value."),
        END_ZONE_DURATION("A distinct series end time zone requires a positive exact duration."),
        END_CONFLICT("DTEND and DURATION cannot be reconciled safely."),
        END_BEFORE_START("The event end precedes its start."),
        ALL_DAY_EMPTY_END("An all-day end must be later than its start unless it is a native cancellation placeholder."),
        INVALID_NUMBER("Invalid numeric value."), INVALID_TIME("Invalid or unsupported date/time."),
        ARITHMETIC("Numeric range or arithmetic overflow."), INVALID_TEXT("Text cannot be serialized safely."),
        PRECISION("Date/time precision cannot be represented without changing the event."),
        DATE_RANGE("Date/time is outside the supported year range."),
        FOLD("A repeated local time would change the saved occurrence."),
        EXRULE("Legacy EXRULE series cannot be exported safely as RFC5545 iCalendar."),
        LIMIT_BYTES("Calendar exceeds the 2 MiB complete-export limit."),
        LIMIT_EVENTS("Calendar exceeds the complete-export limit of 2000 events."),
        LIMIT_ATTENDEES("Calendar exceeds the complete-export attendee limit."),
        NO_EVENTS("The calendar has no events to export."), NO_CALENDAR("The calendar no longer exists."),
        PERMISSION("Allow calendar read access."), CHANGED("Calendar changed during export. Try again."),
        CANCELLED("Calendar query cancelled or timed out. Please reload."),
        UNAVAILABLE("Calendar storage is unavailable."), UNKNOWN("Calendar export failed for an unknown reason.");
        final String text;
        Reason(String text){this.text=text;}
    }
    private static final Set<String> DIAGNOSTIC_FIELDS=new HashSet<>();
    static {
        DIAGNOSTIC_FIELDS.add("document");DIAGNOSTIC_FIELDS.add("attendee");
        for(String[] fields:new String[][]{CAL,EVENT,ATTENDEE})for(String field:fields)DIAGNOSTIC_FIELDS.add(key(field));
    }
    private static final class Diagnostic {
        Phase phase=Phase.CAPTURE_CALENDAR;String field="document";Long eventId;
        void at(Phase phase){this.phase=phase;field="document";eventId=null;}
        void event(ContentValues event){
            field=Events._ID;Object id=event.get(Events._ID);
            eventId=id instanceof Long && (Long)id>0?(Long)id:null;
        }
        CalendarRepository.Failure failure(Reason reason){return failure(reason,null);}
        CalendarRepository.Failure failure(Reason reason,Invalid invalid){
            // Details belong to this exception, never mutable per-event/request state.
            String durations=(reason==Reason.END_CONFLICT||reason==Reason.END_BEFORE_START)&&invalid!=null&&invalid.endStart!=null
                ?"|"+invalid.endStart+"|"+invalid.storedDuration:"";
            return fail("ICS_EXPORT|"+reason.name()+"|"+phase.name()+"|"+field+"|"+(eventId==null?"-":eventId)+durations);
        }
    }
    private static final class Invalid extends IllegalArgumentException {
        final Reason reason;final String endStart,storedDuration;
        Invalid(Reason reason){this(reason,null,null);}
        Invalid(Reason reason,String endStart,String storedDuration){
            this.reason=reason;this.endStart=endStart;this.storedDuration=storedDuration;
        }
    }
    private static void require(boolean valid,Reason reason){if(!valid)throw new Invalid(reason);}
    private static Reason knownFailure(String message){
        if(message==null)return Reason.UNKNOWN;
        switch(message){
            case "Allow calendar read access.":case "Allow calendar access":return Reason.PERMISSION;
            case "The calendar no longer exists.":return Reason.NO_CALENDAR;
            case "The calendar has no events to export.":return Reason.NO_EVENTS;
            case "Calendar exceeds the 2 MiB complete-export limit.":return Reason.LIMIT_BYTES;
            case "Calendar exceeds the complete-export limit of 2000 events.":return Reason.LIMIT_EVENTS;
            case "Calendar exceeds the complete-export attendee limit.":return Reason.LIMIT_ATTENDEES;
            case "Calendar changed during export. Try again.":return Reason.CHANGED;
            case "Legacy EXRULE series cannot be exported safely as RFC5545 iCalendar. Nothing exported.":return Reason.EXRULE;
            case "Calendar contains invalid text. Nothing exported.":return Reason.INVALID_TEXT;
            case "Calendar query cancelled or timed out. Please reload.":return Reason.CANCELLED;
            case "Calendar storage is unavailable":return Reason.UNAVAILABLE;
            default:return Reason.UNKNOWN;
        }
    }
    /** Reconstruct display text from a closed vocabulary; never display foreign messages or causes. */
    static String error(CalendarRepository.Failure failure){
        String raw=failure.getMessage();
        if(raw!=null)try{
            String[] parts=raw.split("\\|",-1);
            if((parts.length==5||parts.length==7)&&parts[0].equals("ICS_EXPORT")&&DIAGNOSTIC_FIELDS.contains(parts[3])){
                Reason reason=Reason.valueOf(parts[1]);Phase phase=Phase.valueOf(parts[2]);
                String id=parts[4],durations="";
                if(parts.length==7){
                    require((reason==Reason.END_CONFLICT||reason==Reason.END_BEFORE_START)
                        &&phase==Phase.ENCODE_EVENT&&parts[3].equals(Events.DTEND));
                    require(diagnosticMillis(parts[5],true)&&diagnosticMillis(parts[6],false));
                    durations="; end-start="+displayMillis(parts[5])+"; stored-duration="+displayMillis(parts[6]);
                }
                if(id.equals("-")||id.matches("[1-9][0-9]{0,18}")&&Long.parseLong(id)>0)
                    return reason.text+" [ICS_"+reason.name()+"; phase="+phase.name()+"; field="+parts[3]
                        +(id.equals("-")?"":"; event ID="+id)+durations+"]";
            }
        }catch(IllegalArgumentException ignored){/* Invalid or foreign diagnostics are generic. */}
        Reason reason=knownFailure(raw);
        return reason.text+" [ICS_"+reason.name()+"]";
    }
    /** Accept canonical signed longs or field-specific static markers, never raw text. */
    private static boolean diagnosticMillis(String value,boolean elapsed){
        if(value.equals("overflow")||!elapsed&&(value.equals("invalid")||value.equals("unavailable")))return true;
        try{long millis=Long.parseLong(value);return value.equals(Long.toString(millis))&&(elapsed||millis>=0);}
        catch(NumberFormatException invalid){return false;}
    }
    private static String displayMillis(String value){
        return value.equals("overflow")||value.equals("invalid")||value.equals("unavailable")?value:value+" ms";
    }
    private static String elapsedMillis(long start,long end){
        try{return Long.toString(Math.subtractExact(end,start));}
        catch(ArithmeticException overflow){return "overflow";}
    }
    private static String storedMillis(String raw,boolean allDay){
        if(raw==null||raw.isEmpty())return "unavailable";
        try{
            // Reuse the exact accepted native parser/bounds/all-day rules. Its
            // canonical result is P<n>D or PT<n>S; do not parse raw input twice.
            String normalized=duration(raw,allDay);
            long units=Long.parseLong(normalized.substring(allDay?1:2,normalized.length()-1));
            return Long.toString(Math.multiplyExact(units,allDay?86400000L:1000L));
        }catch(IllegalArgumentException invalid){return "invalid";}
        catch(ArithmeticException overflow){return "overflow";}
    }
    private static CalendarRepository.Failure fail(String text){return new CalendarRepository.Failure(text);}
    private static void require(boolean valid){if(!valid)throw new IllegalArgumentException();}
    private static String key(String field){return field.startsWith("#")?field.substring(1):field;}
    private static String[] columns(String[] fields){return Arrays.stream(fields).map(CalendarExport::key).toArray(String[]::new);}
    private static String text(ContentValues row,String key){String s=row.getAsString(key);return s==null?"":s;}
    private static boolean has(ContentValues row,String key){return !text(row,key).isEmpty();}
    private static long number(ContentValues row,String key){Long n=row.getAsLong(key);require(n!=null);return n;}
    private static long optional(ContentValues row,String key,long fallback){return row.containsKey(key)?number(row,key):fallback;}
    private static boolean recurring(ContentValues row){return has(row,Events.RRULE)||has(row,Events.RDATE);}
    private static ContentValues row(Cursor cursor,String[] fields,Diagnostic diagnostic){
        ContentValues row=new ContentValues();
        for(int i=0;i<fields.length;i++){
            String field=fields[i];diagnostic.field=key(field);
            if(!cursor.isNull(i)){
                if(field.startsWith("#")){
                    long value=cursor.getLong(i);row.put(key(field),value);
                    if(fields==EVENT&&key(field).equals(Events.DTEND)){
                        // Native cursors may coerce empty/foreign text to zero.
                        // Retain only a flag, not raw text or its parse exception.
                        try{if(Long.parseLong(cursor.getString(i))!=value)row.put(COERCED_DTEND,1);}
                        catch(NumberFormatException invalid){row.put(COERCED_DTEND,1);}
                    }
                    if((fields==EVENT&&key(field).equals(Events._ID))||(fields==ATTENDEE&&key(field).equals(Attendees.EVENT_ID)))
                        diagnostic.eventId=value>0?value:null;
                }else{String value=cursor.getString(i);require(value.length()<=MAX_BYTES,Reason.LIMIT_BYTES);row.put(field,value);}
            }
        }return row;
    }
    private static final class Snapshot {
        ContentValues calendar;
        final List<ContentValues> events=new ArrayList<>();
        final Map<Long,List<ContentValues>> guests=new HashMap<>();
        private int budget;
        void account(ContentValues row,Diagnostic diagnostic)throws CalendarRepository.Failure{
            for(Map.Entry<String,Object> entry:row.valueSet()){
                Object value=entry.getValue();if(value==null)continue;
                budget+=value.toString().getBytes(StandardCharsets.UTF_8).length+32;
                if(budget>MAX_BYTES){diagnostic.field="document";diagnostic.eventId=null;throw fail("Calendar exceeds the 2 MiB complete-export limit.");}
            }
        }
    }
    private static Snapshot capture(Context app,long id,CancellationSignal cancel,Diagnostic diagnostic)throws CalendarRepository.Failure{
        diagnostic.at(Phase.CAPTURE_CALENDAR);
        if(!CalendarRepository.canRead(app))throw fail("Allow calendar read access.");
        Snapshot snapshot=new Snapshot();
        try(Cursor c=CalendarRepository.query(app,ContentUris.withAppendedId(Calendars.CONTENT_URI,id),columns(CAL),null,null,null,cancel)){
            if(!c.moveToFirst())throw fail("The calendar no longer exists.");snapshot.calendar=row(c,CAL,diagnostic);snapshot.account(snapshot.calendar,diagnostic);diagnostic.field="document";
        }
        Map<String,Long> sync=new HashMap<>();diagnostic.at(Phase.CAPTURE_EVENT);
        try(Cursor c=CalendarRepository.query(app,Events.CONTENT_URI,columns(EVENT),Events.CALENDAR_ID+"=? AND "+Events.DELETED+"=0",
                new String[]{Long.toString(id)},Events._ID+" ASC",cancel)){
            while(c.moveToNext()){
                if(snapshot.events.size()>=MAX_EVENTS)throw fail("Calendar exceeds the complete-export limit of 2000 events.");
                ContentValues event=row(c,EVENT,diagnostic);snapshot.account(event,diagnostic);snapshot.events.add(event);
                diagnostic.field=Events._SYNC_ID;
                if(has(event,Events._SYNC_ID))require(sync.put(text(event,Events._SYNC_ID),number(event,Events._ID))==null);
                diagnostic.at(Phase.CAPTURE_EVENT);
            }
        }
        if(snapshot.events.isEmpty())throw fail("The calendar has no events to export.");
        diagnostic.at(Phase.RELATION);
        Set<Long> ids=new HashSet<>();for(ContentValues event:snapshot.events){
            diagnostic.event(event);
            long eventId=number(event,Events._ID);require(ids.add(eventId));
            if(has(event,Events.ORIGINAL_SYNC_ID)){
                diagnostic.field=Events.ORIGINAL_SYNC_ID;
                Long original=sync.get(text(event,Events.ORIGINAL_SYNC_ID));require(original!=null);
                diagnostic.field=Events.ORIGINAL_ID;
                require(!event.containsKey(Events.ORIGINAL_ID)||number(event,Events.ORIGINAL_ID)==original);event.put(Events.ORIGINAL_ID,original);
            }
        }
        diagnostic.at(Phase.CAPTURE_ATTENDEE);
        // Attendees endpoint joins Events/Calendars in native CalendarProvider. Never query Reminders.
        try(Cursor c=CalendarRepository.query(app,Attendees.CONTENT_URI,columns(ATTENDEE),"Events."+Events.CALENDAR_ID+"=? AND Events."+Events.DELETED+"=0",
                new String[]{Long.toString(id)},"Attendees._id ASC",cancel)){
            int count=0;while(c.moveToNext()){
                if(++count>MAX_ATTENDEES)throw fail("Calendar exceeds the complete-export attendee limit.");
                ContentValues guest=row(c,ATTENDEE,diagnostic);snapshot.account(guest,diagnostic);diagnostic.field=Attendees.EVENT_ID;long event=number(guest,Attendees.EVENT_ID);require(ids.contains(event));
                snapshot.guests.computeIfAbsent(event,unused->new ArrayList<>()).add(guest);
                diagnostic.at(Phase.CAPTURE_ATTENDEE);
            }
        }
        return snapshot;
    }
    static byte[] export(Context app,long id,CancellationSignal cancel)throws CalendarRepository.Failure{
        Diagnostic diagnostic=new Diagnostic();
        try{
            String stamp=TIME.format(LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC))+"Z";
            byte[] first=encode(capture(app,id,cancel,diagnostic),stamp,cancel,diagnostic);
            byte[] second=encode(capture(app,id,cancel,diagnostic),stamp,cancel,diagnostic);
            diagnostic.at(Phase.COMPARE);
            if(!Arrays.equals(first,second))throw fail("Calendar changed during export. Try again.");
            cancel.throwIfCanceled();return first;
        }catch(CalendarRepository.Failure failure){
            throw diagnostic.failure(knownFailure(failure.getMessage()));
        }catch(RuntimeException invalid){
            Reason reason=invalid instanceof Invalid?((Invalid)invalid).reason:
                invalid instanceof NumberFormatException?Reason.INVALID_NUMBER:
                invalid instanceof DateTimeException?Reason.INVALID_TIME:
                invalid instanceof ArithmeticException?Reason.ARITHMETIC:
                invalid instanceof IllegalArgumentException?Reason.INVALID_VALUE:
                invalid instanceof SecurityException?Reason.PERMISSION:
                invalid instanceof android.os.OperationCanceledException?Reason.CANCELLED:Reason.UNKNOWN;
            if(reason==Reason.UNKNOWN){diagnostic.field="document";diagnostic.eventId=null;}
            throw diagnostic.failure(reason,invalid instanceof Invalid?(Invalid)invalid:null);
        }
    }
    /** Read-only bridge for existing-row import comparison. Reuse the export/provider
     * contract instead of a second, subtly different duration/zone/metadata mapper.
     * Supplied rows are a complete candidate UID group in one target calendar. */
    static de.blockzeit.core.Icalendar.Document normalizeForImport(ContentValues calendar,List<ContentValues> events,
            Map<Long,List<ContentValues>> guests,ZoneId targetZone,CancellationSignal cancel)throws CalendarRepository.Failure {
        Snapshot snapshot=new Snapshot();Diagnostic diagnostic=new Diagnostic();snapshot.calendar=new ContentValues(calendar);
        Map<String,Long> sync=new HashMap<>();
        require(events.size()<=MAX_EVENTS);
        for(ContentValues original:events) {
            cancel.throwIfCanceled();ContentValues event=new ContentValues();
            // capture() omits nulls and types numeric columns as Long; preserve that
            // same distinction (especially absent DTEND, status and native defaults).
            for(String field:EVENT) {
                String key=key(field);if(original.get(key)==null)continue;
                if(field.startsWith("#"))event.put(key,original.getAsLong(key));else event.put(key,original.getAsString(key));
            }
            snapshot.account(event,diagnostic);snapshot.events.add(event);
            if(has(event,Events._SYNC_ID))require(sync.put(text(event,Events._SYNC_ID),number(event,Events._ID))==null);
            List<ContentValues> attendees=new ArrayList<>();
            for(ContentValues originalGuest:guests.getOrDefault(number(event,Events._ID),Collections.emptyList())) {
                ContentValues attendee=new ContentValues();
                for(String field:ATTENDEE) {
                    String key=key(field);if(originalGuest.get(key)==null)continue;
                    if(field.startsWith("#"))attendee.put(key,originalGuest.getAsLong(key));else attendee.put(key,originalGuest.getAsString(key));
                }
                snapshot.account(attendee,diagnostic);attendees.add(attendee);
            }
            snapshot.guests.put(number(event,Events._ID),attendees);
        }
        for(ContentValues event:snapshot.events)if(has(event,Events.ORIGINAL_SYNC_ID)) {
            Long parent=sync.get(text(event,Events.ORIGINAL_SYNC_ID));require(parent!=null);
            require(!event.containsKey(Events.ORIGINAL_ID)||number(event,Events.ORIGINAL_ID)==parent);event.put(Events.ORIGINAL_ID,parent);
        }
        byte[] encoded=encode(snapshot,"20000101T000000Z",cancel,diagnostic);
        try {
            return de.blockzeit.core.Icalendar.parse(new java.io.ByteArrayInputStream(encoded),targetZone,cancel::isCanceled);
        }catch(java.io.IOException invalid){throw fail("Existing event group cannot be compared safely.");}
    }
    private static byte[] encode(Snapshot s,String stamp,CancellationSignal cancel,Diagnostic diagnostic)throws CalendarRepository.Failure{
        diagnostic.at(Phase.ENCODE_CALENDAR);
        Lines out=new Lines(cancel,diagnostic);Map<Long,ContentValues> ids=new HashMap<>();Set<String> uids=new HashSet<>();
        Map<Long,String> uidById=new HashMap<>();SortedSet<String> zones=new TreeSet<>();
        for(ContentValues event:s.events){diagnostic.at(Phase.ENCODE_EVENT);diagnostic.event(event);require(number(event,Events._ID)>0);require(ids.put(number(event,Events._ID),event)==null);}
        for(ContentValues event:s.events)if(!event.containsKey(Events.ORIGINAL_ID)){
            diagnostic.at(Phase.ENCODE_EVENT);diagnostic.event(event);diagnostic.field=Events.UID_2445;
            String uid=text(event,Events.UID_2445);
            if(uid.isEmpty()){
                diagnostic.at(Phase.ENCODE_CALENDAR);diagnostic.field=Calendars._ID;
                uid=UUID.nameUUIDFromBytes((text(s.calendar,Calendars.NAME)+":"+number(s.calendar,Calendars._ID)
                    +":"+number(event,Events._ID)).getBytes(StandardCharsets.UTF_8))+"@blockzeit.local";
                diagnostic.at(Phase.ENCODE_EVENT);diagnostic.event(event);diagnostic.field=Events.UID_2445;
            }
            require(uids.add(uid));uidById.put(number(event,Events._ID),uid);
        }
        diagnostic.at(Phase.ENCODE_CALENDAR);
        out.line("BEGIN:VCALENDAR");out.line("VERSION:2.0");out.line("PRODID:-//Blockzeit//Calendar export//EN");out.line("CALSCALE:GREGORIAN");
        diagnostic.field=Calendars.CALENDAR_DISPLAY_NAME;
        out.line("X-WR-CALNAME:"+escape(text(s.calendar,Calendars.CALENDAR_DISPLAY_NAME)));
        Set<String> exceptions=new HashSet<>();Lines events=new Lines(cancel,diagnostic);
        for(ContentValues event:s.events){
            diagnostic.at(Phase.RELATION);diagnostic.event(event);
            cancel.throwIfCanceled();ContentValues master=event;String uid=uidById.get(number(event,Events._ID));
            if(event.containsKey(Events.ORIGINAL_ID)){
                diagnostic.field=Events.ORIGINAL_ID;
                master=ids.get(number(event,Events.ORIGINAL_ID));require(master!=null&&!master.containsKey(Events.ORIGINAL_ID)&&recurring(master)&&!recurring(event));
                diagnostic.field=Events.ORIGINAL_INSTANCE_TIME;
                uid=uidById.get(number(master,Events._ID));require(exceptions.add(uid+":"+number(event,Events.ORIGINAL_INSTANCE_TIME)));
                diagnostic.event(master);diagnostic.field=Events.ALL_DAY;
                long fallbackAllDay=number(master,Events.ALL_DAY);
                diagnostic.event(event);diagnostic.field=Events.ORIGINAL_ALL_DAY;
                long originalAllDay=optional(event,Events.ORIGINAL_ALL_DAY,fallbackAllDay);
                diagnostic.event(master);diagnostic.field=Events.ALL_DAY;
                long expectedAllDay=number(master,Events.ALL_DAY);
                diagnostic.event(event);diagnostic.field=Events.ORIGINAL_ALL_DAY;
                require(originalAllDay==expectedAllDay);
            }else {diagnostic.field=event.containsKey(Events.ORIGINAL_INSTANCE_TIME)?Events.ORIGINAL_INSTANCE_TIME:Events.ORIGINAL_ALL_DAY;
                require(!event.containsKey(Events.ORIGINAL_INSTANCE_TIME)&&!event.containsKey(Events.ORIGINAL_ALL_DAY));}
            writeEvent(events,event,master,uid,stamp,zones,s.guests.getOrDefault(number(event,Events._ID),Collections.emptyList()),diagnostic);
        }
        diagnostic.at(Phase.TIMEZONE);diagnostic.field=Events.EVENT_TIMEZONE;
        for(String zone:zones)timezone(out,ZoneId.of(zone));
        diagnostic.at(Phase.SERIALIZE);
        out.append(events);out.line("END:VCALENDAR");return out.bytes.toByteArray();
    }
    private static void writeEvent(Lines out,ContentValues event,ContentValues master,String uid,String stamp,Set<String> zones,List<ContentValues> guests,Diagnostic diagnostic)throws CalendarRepository.Failure{
        diagnostic.at(Phase.ENCODE_EVENT);diagnostic.event(event);diagnostic.field=Events.ALL_DAY;
        boolean allDay=number(event,Events.ALL_DAY)==1;require(number(event,Events.ALL_DAY)==0||allDay);
        diagnostic.field=Events.DTSTART;long start=number(event,Events.DTSTART);
        diagnostic.field=Events.EVENT_TIMEZONE;ZoneId zone=zone(event,allDay);
        diagnostic.field=Events.UID_2445;
        out.line("BEGIN:VEVENT");out.line("UID:"+escape(uid));out.line("DTSTAMP:"+stamp);
        if(event!=master){
            diagnostic.field=Events.ORIGINAL_INSTANCE_TIME;long original=number(event,Events.ORIGINAL_INSTANCE_TIME);
            diagnostic.event(master);diagnostic.field=Events.ALL_DAY;boolean masterAllDay=number(master,Events.ALL_DAY)==1;
            diagnostic.field=Events.EVENT_TIMEZONE;ZoneId masterZone=zone(master,masterAllDay);
            diagnostic.event(event);diagnostic.field=Events.ORIGINAL_INSTANCE_TIME;moment(out,"RECURRENCE-ID",original,masterAllDay,masterZone,zones);
        }
        diagnostic.field=Events.DTSTART;moment(out,"DTSTART",start,allDay,zone,zones);
        if(event.containsKey(Events.DTEND)){
            diagnostic.field=Events.DTEND;long end=number(event,Events.DTEND);
            // RFC5545 3.8.5.3: DTEND on an RRULE/RDATE master defines the same
            // exact elapsed duration for every instance. It is valid iCalendar,
            // even though native provider INSERT normally requires DURATION.
            // Keep that existing representation when the native duration agrees
            // for the whole series, not merely a nominal day's first endpoint.
            boolean conflict=has(event,Events.DURATION)&&!(recurring(event)
                &&sameSeriesDuration(text(event,Events.DURATION),allDay,start,end));
            String effectiveDuration=null;
            if(conflict){
                // Only an active, linked-as-master recurrence has the proven native
                // Instances rule: each END = BEGIN + Duration.getMillis(). Do not
                // extend it to standalone rows, exceptions or canceled placeholders.
                if(event==master&&recurring(event)
                    &&optional(event,Events.STATUS,Events.STATUS_CONFIRMED)!=Events.STATUS_CANCELED){
                    try{effectiveDuration=duration(text(event,Events.DURATION),allDay);}
                    catch(IllegalArgumentException|ArithmeticException invalid){/* Keep the private conflict diagnostic. */}
                }
                if(effectiveDuration==null)
                    throw new Invalid(Reason.END_CONFLICT,elapsedMillis(start,end),storedMillis(text(event,Events.DURATION),allDay));
            }
            if(effectiveDuration!=null){
                require(!event.containsKey(COERCED_DTEND),Reason.INVALID_NUMBER);
                // Archive only a real, strictly validated old endpoint. Never hide
                // malformed numeric/precision, year or zone values in an X field.
                // Its position relative to DTSTART is not the native instance end.
                diagnostic.field=Events.EVENT_END_TIMEZONE;
                ZoneId endZone=has(event,Events.EVENT_END_TIMEZONE)&&!allDay?ZoneId.of(text(event,Events.EVENT_END_TIMEZONE)):zone;
                diagnostic.field=Events.DTEND;
                Lines validation=new Lines(out.cancel,diagnostic);
                moment(validation,"DTEND",end,allDay,endZone,zones);
                // The effective end is an instant, not another local wall-time
                // property: a later DST fold is unambiguous under exact seconds.
                diagnostic.field=Events.DURATION;
                long units=Long.parseLong(effectiveDuration.substring(allDay?1:2,effectiveDuration.length()-1));
                long effectiveEnd=Math.addExact(start,Math.multiplyExact(units,allDay?86400000L:1000L));
                require(effectiveEnd>=start,Reason.END_BEFORE_START);
                require(!allDay||effectiveEnd>start,Reason.ALL_DAY_EMPTY_END);
                moment(validation,"DTEND",effectiveEnd,allDay,ZoneOffset.UTC,new HashSet<>());
                out.line("DURATION:"+effectiveDuration);
                // RFC5545 X-properties preserve provenance, not recurrence bounds.
                // This is Unix epoch milliseconds, never a guessed UNTIL or date.
                diagnostic.field=Events.DTEND;
                out.line("X-BLOCKZEIT-PROVIDER-DTEND:"+end);
                if(!allDay&&has(event,Events.EVENT_END_TIMEZONE)){
                    diagnostic.field=Events.EVENT_END_TIMEZONE;
                    out.line("X-BLOCKZEIT-PROVIDER-END-TIMEZONE:"+escape(text(event,Events.EVENT_END_TIMEZONE)));
                }
            }else {
                // Here DTEND really is the effective end; retain strict chronology.
                if(end<start)throw new Invalid(Reason.END_BEFORE_START,elapsedMillis(start,end),storedMillis(text(event,Events.DURATION),allDay));
                if(end==start&&allDay){
                    // AOSP fabricates DTSTART=DTEND=ORIGINAL_INSTANCE_TIME for canceled
                    // instances without times. This is an identity-only tombstone, not
                    // a zero-day appointment. Keep the canceled VEVENT and its metadata,
                    // but omit only that synthetic end (RFC5545 3.6.1/3.8.2.2).
                    // Never invent a day or borrow the master's possibly multi-day duration.
                    require(event!=master&&number(master,Events.ALL_DAY)==1
                        &&optional(event,Events.STATUS,Events.STATUS_CONFIRMED)==Events.STATUS_CANCELED
                        &&number(event,Events.ORIGINAL_INSTANCE_TIME)==start,Reason.ALL_DAY_EMPTY_END);
                }else if(end==start)out.line("DURATION:PT0S");
                else {
                    diagnostic.field=Events.EVENT_END_TIMEZONE;
                    ZoneId endZone=has(event,Events.EVENT_END_TIMEZONE)&&!allDay?ZoneId.of(text(event,Events.EVENT_END_TIMEZONE)):zone;
                    diagnostic.field=Events.DTEND;moment(out,"DTEND",end,allDay,endZone,zones);
                }
            }
        }else if(recurring(event)||has(event,Events.DURATION)){
            diagnostic.field=Events.DURATION;String normalized=duration(text(event,Events.DURATION),allDay);
            boolean writeDuration=true;
            if(event==master&&recurring(event)&&!allDay
                &&optional(event,Events.STATUS,Events.STATUS_CONFIRMED)!=Events.STATUS_CANCELED
                &&has(event,Events.EVENT_END_TIMEZONE)){
                diagnostic.field=Events.EVENT_END_TIMEZONE;ZoneId endZone=ZoneId.of(text(event,Events.EVENT_END_TIMEZONE));
                if(!endZone.equals(zone)){
                    // Native Duration.getMillis() is exact elapsed seconds, including native P1D.
                    // RFC5545 3.8.5.3 gives every DTEND-based recurrence that same exact duration.
                    // This is NOT a conversion of an arbitrary nominal RFC day/week duration.
                    diagnostic.field=Events.DURATION;
                    long seconds=Long.parseLong(normalized.substring(2,normalized.length()-1));
                    require(seconds>0,Reason.END_ZONE_DURATION);
                    long effectiveEnd=Math.addExact(start,Math.multiplyExact(seconds,1000L));
                    diagnostic.field=Events.DTEND;moment(out,"DTEND",effectiveEnd,false,endZone,zones);
                    writeDuration=false;
                }
            }
            if(writeDuration){diagnostic.field=Events.DURATION;out.line("DURATION:"+normalized);}
        }
        else {diagnostic.field=Events.STATUS;require(optional(event,Events.STATUS,Events.STATUS_CONFIRMED)==Events.STATUS_CANCELED);}
        diagnostic.field=Events.TITLE;out.line("SUMMARY:"+escape(text(event,Events.TITLE)));
        for(String[] field:new String[][]{{Events.DESCRIPTION,"DESCRIPTION"},{Events.EVENT_LOCATION,"LOCATION"}})
            if(has(event,field[0])){diagnostic.field=field[0];out.line(field[1]+":"+escape(text(event,field[0])));}
        diagnostic.field=Events.STATUS;long status=optional(event,Events.STATUS,-1);require(status>=-1&&status<=2);
        if(status>=0)out.line("STATUS:"+new String[]{"TENTATIVE","CONFIRMED","CANCELLED"}[(int)status]);
        diagnostic.field=Events.ACCESS_LEVEL;long access=optional(event,Events.ACCESS_LEVEL,0);require(access>=0&&access<=3);
        if(access!=0)out.line("CLASS:"+new String[]{"","CONFIDENTIAL","PRIVATE","PUBLIC"}[(int)access]);
        diagnostic.field=Events.AVAILABILITY;long available=optional(event,Events.AVAILABILITY,Events.AVAILABILITY_BUSY);require(available>=0&&available<=2);
        out.line("TRANSP:"+(available==Events.AVAILABILITY_FREE?"TRANSPARENT":"OPAQUE"));
        if(available==Events.AVAILABILITY_TENTATIVE)out.line("X-ANDROID-AVAILABILITY:2");
        diagnostic.field=Events.EVENT_COLOR;
        if(event.containsKey(Events.EVENT_COLOR))out.line("X-ANDROID-EVENT-COLOR:"+number(event,Events.EVENT_COLOR));
        diagnostic.field=Events.ORGANIZER;
        if(has(event,Events.ORGANIZER))out.line("ORGANIZER:"+address(text(event,Events.ORGANIZER)));
        for(ContentValues guest:guests)attendee(out,guest,diagnostic);
        for(String[] flag:new String[][]{{Events.GUESTS_CAN_MODIFY,"X-ANDROID-GUESTS-CAN-MODIFY"},
                {Events.GUESTS_CAN_INVITE_OTHERS,"X-ANDROID-GUESTS-CAN-INVITE-OTHERS"},{Events.GUESTS_CAN_SEE_GUESTS,"X-ANDROID-GUESTS-CAN-SEE-GUESTS"}})
            if(event.containsKey(flag[0])){diagnostic.field=flag[0];long value=number(event,flag[0]);require(value==0||value==1);out.line(flag[1]+":"+value);}
        diagnostic.field=Events.EXRULE;
        if(has(event,Events.EXRULE))throw fail("Legacy EXRULE series cannot be exported safely as RFC5545 iCalendar. Nothing exported.");
        diagnostic.field=Events.RRULE;
        require(!text(event,Events.RRULE).contains("\n"));
        rules(out,"RRULE",text(event,Events.RRULE),allDay,zone);
        diagnostic.field=Events.EXDATE;require(recurring(event)||!has(event,Events.EXRULE)&&!has(event,Events.EXDATE));
        diagnostic.field=Events.RDATE;dates(out,"RDATE",text(event,Events.RDATE),allDay,zone);
        diagnostic.field=Events.EXDATE;dates(out,"EXDATE",text(event,Events.EXDATE),allDay,zone);
        out.line("END:VEVENT");
    }
    /** Native CalendarInstancesHelper uses Duration.getMillis() for each start:
     * provider P1D is elapsed 86400s for timed rows, NOT RFC nominal DURATION:P1D.
     * Reuse the existing bounded native normalization; emit only the original
     * DTEND when equivalent. DATE ends/durations count exact UTC date labels. */
    private static boolean sameSeriesDuration(String raw,boolean allDay,long start,long end){
        try{
            long millis=Math.subtractExact(end,start);
            if(millis<0||millis%1000!=0||allDay&&millis%86400000!=0)return false;
            return duration(raw,allDay).equals(allDay?"P"+(millis/86400000)+"D":"PT"+(millis/1000)+"S");
        }catch(IllegalArgumentException|ArithmeticException invalid){return false;}
    }
    private static ZoneId zone(ContentValues row,boolean allDay){
        if(allDay)return ZoneOffset.UTC;
        require(has(row,Events.EVENT_TIMEZONE));return ZoneId.of(text(row,Events.EVENT_TIMEZONE));
    }
    private static void moment(Lines out,String name,long millis,boolean allDay,ZoneId zone,Set<String> zones)throws CalendarRepository.Failure{
        require(millis%1000==0,Reason.PRECISION);ZonedDateTime value=Instant.ofEpochMilli(millis).atZone(allDay?ZoneOffset.UTC:zone);
        require(value.getYear()>=1&&value.getYear()<=9999,Reason.DATE_RANGE);
        if(allDay){require(millis%86400000==0,Reason.PRECISION);out.line(name+";VALUE=DATE:"+DATE.format(value));}
        else if(zone.getRules().isFixedOffset()&&zone.getRules().getOffset(Instant.EPOCH).equals(ZoneOffset.UTC))out.line(name+":"+TIME.format(value)+"Z");
        else{
            // RFC5545 resolves a repeated local time to the first occurrence; do not change a later-fold instant.
            require(zone.getRules().getValidOffsets(value.toLocalDateTime()).get(0).equals(value.getOffset()),Reason.FOLD);
            zones.add(zone.getId());out.line(name+";TZID="+parameter(zone.getId())+":"+TIME.format(value));
        }
    }
    private static String escape(String raw){
        return clean(raw).replace("\\","\\\\").replace("\n","\\n").replace(";","\\;").replace(",","\\,");
    }
    private static String clean(String raw){
        String value=raw.replace("\r\n","\n").replace('\r','\n');
        for(int i=0;i<value.length();i++)require(value.charAt(i)!=0x7f&&(value.charAt(i)>=' '||value.charAt(i)=='\n'||value.charAt(i)=='\t'));
        return value;
    }
    private static String parameter(String raw){return "\""+clean(raw).replace("^","^^").replace("\n","^n").replace("\"","^'")+"\"";}
    private static String address(String email){
        require(!email.isEmpty()&&!email.contains("\r")&&!email.contains("\n"));
        try{return new URI("mailto",email,null).toASCIIString();}catch(URISyntaxException invalid){throw new IllegalArgumentException(invalid);}
    }
    private static void attendee(Lines out,ContentValues row,Diagnostic diagnostic)throws CalendarRepository.Failure{
        diagnostic.field=Attendees.ATTENDEE_EMAIL;
        String email=text(row,Attendees.ATTENDEE_EMAIL);String uri;
        if(!email.isEmpty())uri=address(email);
        else{
            diagnostic.field=Attendees.ATTENDEE_IDENTITY;
            String identity=text(row,Attendees.ATTENDEE_IDENTITY);require(!identity.isEmpty());
            try{URI parsed=new URI(identity);require(parsed.isAbsolute()&&!identity.contains("\r")&&!identity.contains("\n"));uri=parsed.toASCIIString();}
            catch(URISyntaxException invalid){throw new IllegalArgumentException(invalid);}
        }
        StringBuilder line=new StringBuilder("ATTENDEE");
        diagnostic.field=Attendees.ATTENDEE_NAME;
        if(has(row,Attendees.ATTENDEE_NAME))line.append(";CN=").append(parameter(text(row,Attendees.ATTENDEE_NAME)));
        diagnostic.field=Attendees.ATTENDEE_TYPE;long type=optional(row,Attendees.ATTENDEE_TYPE,0);
        diagnostic.field=Attendees.ATTENDEE_STATUS;long status=optional(row,Attendees.ATTENDEE_STATUS,0);
        diagnostic.field=Attendees.ATTENDEE_RELATIONSHIP;long relation=optional(row,Attendees.ATTENDEE_RELATIONSHIP,0);
        diagnostic.field=type<0||type>3?Attendees.ATTENDEE_TYPE:status<0||status>4?Attendees.ATTENDEE_STATUS:Attendees.ATTENDEE_RELATIONSHIP;
        require(type>=0&&type<=3&&status>=0&&status<=4&&relation>=0&&relation<=4);
        line.append(";ROLE=").append(type==Attendees.TYPE_OPTIONAL?"OPT-PARTICIPANT":type==Attendees.TYPE_RESOURCE?"NON-PARTICIPANT":"REQ-PARTICIPANT");
        if(type==Attendees.TYPE_RESOURCE)line.append(";CUTYPE=RESOURCE");
        line.append(";PARTSTAT=").append(new String[]{"NEEDS-ACTION","ACCEPTED","DECLINED","INVITED","TENTATIVE"}[(int)status].replace("INVITED","NEEDS-ACTION"));
        line.append(";X-ANDROID-RELATIONSHIP=").append(relation);
        diagnostic.field=Attendees.ATTENDEE_IDENTITY;
        if(has(row,Attendees.ATTENDEE_IDENTITY))line.append(";X-ANDROID-IDENTITY=").append(parameter(text(row,Attendees.ATTENDEE_IDENTITY)));
        diagnostic.field=Attendees.ATTENDEE_ID_NAMESPACE;
        if(has(row,Attendees.ATTENDEE_ID_NAMESPACE))line.append(";X-ANDROID-ID-NAMESPACE=").append(parameter(text(row,Attendees.ATTENDEE_ID_NAMESPACE)));
        line.append(':').append(uri);diagnostic.field="attendee";out.line(line.toString());
    }
    private static void dates(Lines out,String property,String raw,boolean allDay,ZoneId eventZone)throws CalendarRepository.Failure{
        if(raw.isEmpty())return;
        for(String group:raw.split("\n",-1)){
            ZoneId zone=eventZone;int separator=group.indexOf(';');
            if(separator>=0){zone=ZoneId.of(group.substring(0,separator));group=group.substring(separator+1);}
            List<String> dates=new ArrayList<>();for(String date:group.split(",",-1)){
                if(allDay){
                    if(date.matches("[0-9]{8}"))dates.add(DATE.format(LocalDate.parse(date,DATE)));
                    else{require(date.matches("[0-9]{8}T000000Z?"));dates.add(DATE.format(LocalDate.parse(date.substring(0,8),DATE)));}
                }else{
                    boolean utc=date.endsWith("Z");String local=utc?date.substring(0,date.length()-1):date;
                    LocalDateTime value=LocalDateTime.parse(local,TIME);ZoneId source=utc?ZoneOffset.UTC:zone;
                    require(source.getRules().getValidOffsets(value).size()==1);
                    dates.add(TIME.format(value.atZone(source).withZoneSameInstant(ZoneOffset.UTC))+"Z");
                }
            }
            out.line(property+(allDay?";VALUE=DATE":"")+":"+String.join(",",dates));
        }
    }
    private static void rules(Lines out,String property,String raw,boolean allDay,ZoneId zone)throws CalendarRepository.Failure{
        if(raw.isEmpty())return;
        for(String value:raw.split("\n",-1)){
            rule(value);List<String> parts=new ArrayList<>();for(String part:value.split(";")){
                if(part.startsWith("UNTIL=")){
                    String date=part.substring(6);
                    if(allDay){if(date.length()>8){require(date.matches("[0-9]{8}T000000Z?"));date=date.substring(0,8);}}
                    else{require(date.length()>8);if(!date.endsWith("Z")){
                        LocalDateTime time=LocalDateTime.parse(date,TIME);require(zone.getRules().getValidOffsets(time).size()==1);
                        date=TIME.format(time.atZone(zone).withZoneSameInstant(ZoneOffset.UTC))+"Z";
                    }}part="UNTIL="+date;
                }parts.add(part);
            }out.line(property+":"+String.join(";",parts));
        }
    }
    /** Complete historical transition table plus verified perpetual future rules from java.time.
     * Fixed-offset zones get one STANDARD observance. No device-default/floating times are emitted.
     */
    private static void timezone(Lines out,ZoneId zone)throws CalendarRepository.Failure{
        ZoneRules rules=zone.getRules();out.line("BEGIN:VTIMEZONE");out.line("TZID:"+escape(zone.getId()));
        List<ZoneOffsetTransition> history=rules.getTransitions();ZoneOffset initial=history.isEmpty()?rules.getOffset(Instant.EPOCH):history.get(0).getOffsetBefore();
        out.line("BEGIN:STANDARD");out.line("DTSTART:00010101T000000");out.line("TZOFFSETFROM:"+offset(initial));out.line("TZOFFSETTO:"+offset(initial));out.line("END:STANDARD");
        Map<String,List<ZoneOffsetTransition>> groups=new LinkedHashMap<>();
        for(ZoneOffsetTransition transition:history){
            String group=(rules.isDaylightSavings(transition.getInstant())?"DAYLIGHT":"STANDARD")+transition.getOffsetBefore()+":"+transition.getOffsetAfter();
            groups.computeIfAbsent(group,unused->new ArrayList<>()).add(transition);
        }
        for(List<ZoneOffsetTransition> group:groups.values()){
            ZoneOffsetTransition first=group.get(0);String kind=rules.isDaylightSavings(first.getInstant())?"DAYLIGHT":"STANDARD";
            out.line("BEGIN:"+kind);out.line("DTSTART:"+TIME.format(first.getDateTimeBefore()));
            out.line("TZOFFSETFROM:"+offset(first.getOffsetBefore()));out.line("TZOFFSETTO:"+offset(first.getOffsetAfter()));
            if(group.size()>1){List<String> dates=new ArrayList<>();for(int i=1;i<group.size();i++)dates.add(TIME.format(group.get(i).getDateTimeBefore()));out.line("RDATE:"+String.join(",",dates));}
            out.line("END:"+kind);
        }
        int year=history.isEmpty()?1970:history.get(history.size()-1).getDateTimeBefore().getYear();
        for(ZoneOffsetTransitionRule future:rules.getTransitionRules()){
            ZoneOffsetTransition first=future.createTransition(year);
            if(!history.isEmpty()&&!first.getInstant().isAfter(history.get(history.size()-1).getInstant()))first=future.createTransition(year+1);
            String recurrence=futureRule(future);observance(out,first,rules,recurrence);
        }
        out.line("END:VTIMEZONE");
    }
    private static String futureRule(ZoneOffsetTransitionRule rule){
        // Derive the local-wall rule, then prove it against every year of the Gregorian 400-year cycle.
        Set<Integer> days=new TreeSet<>();Set<DayOfWeek> weekdays=new HashSet<>();Set<Integer> months=new HashSet<>();Set<LocalTime> times=new HashSet<>();
        for(int y=2000;y<2400;y++){
            LocalDateTime time=rule.createTransition(y).getDateTimeBefore();days.add(time.getDayOfMonth());weekdays.add(time.getDayOfWeek());months.add(time.getMonthValue());times.add(time.toLocalTime());
        }
        require(months.size()==1&&times.size()==1);boolean useWeekday=days.size()>1;require(!useWeekday||weekdays.size()==1);
        int month=months.iterator().next();DayOfWeek weekday=weekdays.iterator().next();
        for(int y=2000;y<2400;y++){
            List<LocalDate> matches=new ArrayList<>();for(int day:days)if(day<=YearMonth.of(y,month).lengthOfMonth()){
                LocalDate date=LocalDate.of(y,month,day);if(!useWeekday||date.getDayOfWeek()==weekday)matches.add(date);
            }require(matches.size()==1&&matches.get(0).equals(rule.createTransition(y).getDateTimeBefore().toLocalDate()));
        }
        return "FREQ=YEARLY;BYMONTH="+month+";BYMONTHDAY="+String.join(",",days.stream().map(Object::toString).collect(java.util.stream.Collectors.toList()))
            +(useWeekday?";BYDAY="+new String[]{"MO","TU","WE","TH","FR","SA","SU"}[weekday.getValue()-1]:"");
    }
    private static void observance(Lines out,ZoneOffsetTransition transition,ZoneRules rules,String recurrence)throws CalendarRepository.Failure{
        String kind=rules.isDaylightSavings(transition.getInstant())?"DAYLIGHT":"STANDARD";
        out.line("BEGIN:"+kind);out.line("DTSTART:"+TIME.format(transition.getDateTimeBefore()));
        out.line("TZOFFSETFROM:"+offset(transition.getOffsetBefore()));out.line("TZOFFSETTO:"+offset(transition.getOffsetAfter()));
        if(recurrence!=null)out.line("RRULE:"+recurrence);out.line("END:"+kind);
    }
    private static String offset(ZoneOffset value){
        int seconds=value.getTotalSeconds(),absolute=Math.abs(seconds);
        return String.format(Locale.ROOT,"%s%02d%02d",seconds<0?"-":"+",absolute/3600,absolute/60%60)
            +(absolute%60==0?"":String.format(Locale.ROOT,"%02d",absolute%60));
    }
    private static final class Lines {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final CancellationSignal cancel;private final Diagnostic diagnostic;
        Lines(CancellationSignal cancel,Diagnostic diagnostic){this.cancel=cancel;this.diagnostic=diagnostic;}
        void line(String text)throws CalendarRepository.Failure{
            cancel.throwIfCanceled();byte[] data;
            try{ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));data=new byte[encoded.remaining()];encoded.get(data);}
            catch(CharacterCodingException invalid){diagnostic.phase=Phase.SERIALIZE;throw fail("Calendar contains invalid text. Nothing exported.");}
            require(!text.contains("\r")&&!text.contains("\n"));int begin=0;
            while(begin<data.length){
                int end=Math.min(data.length,begin+(begin==0?75:74));
                while(end<data.length&&(data[end]&0xc0)==0x80)end--;
                if(begin>0)bytes.write(' ');bytes.write(data,begin,end-begin);bytes.write('\r');bytes.write('\n');begin=end;
            }
            if(data.length==0){bytes.write('\r');bytes.write('\n');}limit();
        }
        void append(Lines other)throws CalendarRepository.Failure{cancel.throwIfCanceled();byte[] data=other.bytes.toByteArray();bytes.write(data,0,data.length);limit();}
        private void limit()throws CalendarRepository.Failure{if(bytes.size()>MAX_BYTES){diagnostic.at(Phase.SERIALIZE);throw fail("Calendar exceeds the 2 MiB complete-export limit.");}}
    }
    private static final Pattern DURATION=Pattern.compile("\\+?P(?:([0-9]{1,10})W|(?:([0-9]{1,10})D)?(T)?(?:([0-9]{1,10})H)?(?:([0-9]{1,10})M)?(?:([0-9]{1,10})S)?)");
    /** AOSP also emits P3600S/+P0S, without T. Duration.getMillis sums seconds as int,
     * so every component AND the complete sum must fit before provider normalization.
     * Whole-day seconds are written as days for all-day masters, never rounded. */
    static String duration(String raw,boolean allDay){
        Matcher match=DURATION.matcher(raw);require(match.matches());
        require(match.group(1)!=null||match.group(2)!=null||match.group(4)!=null||match.group(5)!=null||match.group(6)!=null);
        if(match.group(3)!=null)require(match.group(4)!=null||match.group(5)!=null||match.group(6)!=null);
        long seconds=0;int[] groups={1,2,4,5,6};long[] units={604800,86400,3600,60,1};
        for(int i=0;i<groups.length;i++)if(match.group(groups[i])!=null){
            long component=Long.parseLong(match.group(groups[i]));require(component<=Integer.MAX_VALUE);
            seconds=Math.addExact(seconds,Math.multiplyExact(component,units[i]));
        }
        require(seconds<=Integer.MAX_VALUE);
        if(allDay){require(seconds>0&&seconds%86400==0);return "P"+(seconds/86400)+"D";}
        return "PT"+seconds+"S";
    }
    private static void rule(String value){
        if(value==null||value.isEmpty())return;
        Set<String> seen=new HashSet<>();Map<String,String> parts=new HashMap<>();
        for(String part:value.split(";",-1)){
            String[] pair=part.split("=",-1);require(pair.length==2&&!pair[1].isEmpty()&&seen.add(pair[0]));
            String key=pair[0],raw=pair[1];parts.put(key,raw);
            switch(key){
                case "FREQ":require(Set.of("SECONDLY","MINUTELY","HOURLY","DAILY","WEEKLY","MONTHLY","YEARLY").contains(raw));break;
                case "UNTIL":date(raw);break;
                case "COUNT":case "INTERVAL":range(raw,1,Integer.MAX_VALUE,false);break;
                case "WKST":require(raw.matches("MO|TU|WE|TH|FR|SA|SU"));break;
                case "BYDAY":for(String day:raw.split(",",-1)){
                    require(day.matches("[+-]?[0-9]{0,2}(MO|TU|WE|TH|FR|SA|SU)"));
                    String ordinal=day.substring(0,day.length()-2);if(!ordinal.isEmpty())range(ordinal,-53,53,true);
                }break;
                case "BYSECOND":numbers(raw,0,60,false);break;
                case "BYMINUTE":numbers(raw,0,59,false);break;
                case "BYHOUR":numbers(raw,0,23,false);break;
                case "BYMONTHDAY":numbers(raw,-31,31,true);break;
                case "BYYEARDAY":numbers(raw,-366,366,true);break;
                case "BYWEEKNO":numbers(raw,-53,53,true);break;
                case "BYMONTH":numbers(raw,1,12,false);break;
                case "BYSETPOS":numbers(raw,-366,366,true);break;
                default:throw new IllegalArgumentException();
            }
        }
        require(parts.containsKey("FREQ")&&!(parts.containsKey("COUNT")&&parts.containsKey("UNTIL")));
        String freq=parts.get("FREQ");
        if(parts.containsKey("BYWEEKNO"))require(freq.equals("YEARLY"));
        if(parts.containsKey("BYYEARDAY"))require(!Set.of("DAILY","WEEKLY","MONTHLY").contains(freq));
        if(parts.containsKey("BYMONTHDAY"))require(!freq.equals("WEEKLY"));
        if(parts.containsKey("BYSETPOS"))require(parts.keySet().stream().anyMatch(k->k.startsWith("BY")&&!k.equals("BYSETPOS")));
        if(parts.containsKey("BYDAY"))for(String day:parts.get("BYDAY").split(","))if(day.length()>2)
            require((freq.equals("MONTHLY")||freq.equals("YEARLY"))&&!parts.containsKey("BYWEEKNO"));
    }
    private static void range(String raw,int min,int max,boolean noZero){
        require(raw.matches("[+-]?[0-9]{1,10}"));long n=Long.parseLong(raw);require(n>=min&&n<=max&&(!noZero||n!=0));
    }
    private static void numbers(String value,int min,int max,boolean noZero){for(String raw:value.split(",",-1))range(raw,min,max,noZero);}
    private static void date(String raw){
        if(raw.matches("[0-9]{8}"))LocalDate.parse(raw,DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
        else{
            require(raw.matches("[0-9]{8}T[0-9]{6}Z?"));String local=raw.endsWith("Z")?raw.substring(0,15):raw;
            LocalDateTime.parse(local,DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT));
        }
    }
}
