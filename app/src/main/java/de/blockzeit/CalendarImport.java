package de.blockzeit;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import de.blockzeit.core.Icalendar;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.ZoneId;
import java.util.*;

/** Insert-only file import. Run exclusively on CalendarRepository's shared IO lane.
 * A committed intent journal precedes each insert; identity is also in the inserted row.
 * An ambiguous/missing previously imported row is a conflict, never an automatic reinsert. */
final class CalendarImport {
    static final int MAX_PROVIDER_ROWS = 20000;
    private static final String PREFIX = "blockzeit-ics://import/v1/";
    static final class Target {
        final long id; final String identity, name, zone; final ContentValues guard;
        Target(long id, String identity, String name, String zone, ContentValues guard) { this.id=id; this.identity=identity; this.name=name; this.zone=zone; this.guard=new ContentValues(guard); }
    }
    static final class Preview {
        final Target target; final Icalendar.Document document;
        final int fresh, skipped, conflicts;
        final Set<String> eligibleUids;
        Preview(Target t, Icalendar.Document d, int n, int s, int x, Set<String> eligible) {
            target=t; document=d; fresh=n; skipped=s; conflicts=x;
            eligibleUids=Collections.unmodifiableSet(new HashSet<>(eligible));
        }
    }
    static final class Result {
        int inserted, skipped, conflicts; String failure;
    }
    static Target target(Context c, long id, CancellationSignal cancel) throws CalendarRepository.Failure {
        cancel.throwIfCanceled();
        if (!CalendarRepository.canRead(c) || !CalendarRepository.canWrite(c)) throw fail("Import requires both calendar permissions.");
        try (Cursor r = CalendarRepository.query(c, ContentUris.withAppendedId(Calendars.CONTENT_URI,id),
                new String[]{Calendars._ID,Calendars.NAME,Calendars.ACCOUNT_NAME,Calendars.ACCOUNT_TYPE,
                    Calendars.CALENDAR_DISPLAY_NAME,Calendars.CALENDAR_TIME_ZONE,Calendars.CALENDAR_ACCESS_LEVEL,Calendars.OWNER_ACCOUNT},null,null,null,cancel)) {
            if (!r.moveToFirst() || r.getLong(0)!=id) throw fail("The target calendar no longer exists.");
            if (r.getInt(6)<Calendars.CAL_ACCESS_CONTRIBUTOR) throw fail("The target calendar is read-only.");
            if (!CalendarContract.ACCOUNT_TYPE_LOCAL.equals(r.getString(3))) throw fail("Choose a local calendar for file import (or create one below).");
            String zone=r.getString(5);
            if (zone==null || zone.isEmpty()) zone=ZoneId.systemDefault().getId();
            try { ZoneId.of(zone); } catch (RuntimeException e) { throw fail("Unknown target calendar time zone."); }
            StringBuilder scope=new StringBuilder("calendar-v1");
            for(String part:new String[]{""+id,r.getString(1),r.getString(2),r.getString(3),r.getString(7)})
                scope.append(':').append(part==null?-1:part.length()).append(':').append(part==null?"":part);
            String identity=hash(scope.toString());
            ContentValues guard=new ContentValues();
            String[] guarded={Calendars._ID,Calendars.NAME,Calendars.ACCOUNT_NAME,Calendars.ACCOUNT_TYPE,
                Calendars.CALENDAR_DISPLAY_NAME,Calendars.CALENDAR_TIME_ZONE,Calendars.CALENDAR_ACCESS_LEVEL,Calendars.OWNER_ACCOUNT};
            for(int i=0;i<guarded.length;i++)if(i!=4) {
                if(r.isNull(i))guard.putNull(guarded[i]);
                else if(r.getType(i)==Cursor.FIELD_TYPE_INTEGER)guard.put(guarded[i],r.getLong(i));
                else guard.put(guarded[i],r.getString(i));
            }
            return new Target(id,identity,r.getString(4),zone,guard);
        }
    }
    static Preview read(Context c, long calendar, Uri uri, CancellationSignal cancel) throws CalendarRepository.Failure {
        Target t=target(c,calendar,cancel);
        Icalendar.Document document;
        try (InputStream input=c.getContentResolver().openInputStream(uri)) {
            if (input==null) throw new IOException();
            document=Icalendar.parse(input,ZoneId.of(t.zone),cancel::isCanceled);
        } catch (IOException | IllegalArgumentException e) { throw fail("Invalid or unsupported iCalendar file: "+safe(e.getMessage())); }
        return preview(c,t,document,cancel);
    }
    static Preview preview(Context c, Target t, Icalendar.Document d, CancellationSignal cancel) throws CalendarRepository.Failure {
        fresh(c,t,cancel);
        Set<String> oversized=new HashSet<>();
        for(List<Icalendar.Event> group:groups(d).values()) {
            cancel.throwIfCanceled();
            try { batch(t,group); } catch(CalendarRepository.Failure failure) { oversized.add(group.get(0).uid); }
        }
        if(!oversized.isEmpty())d=d.withRejectedGroups(oversized,"UID group exceeds safe atomic provider batch limits");
        Rows rows=rows(c,t,d,cancel);
        int n=0,s=0,x=0; Set<String> eligible=new HashSet<>();
        for (List<Icalendar.Event> group:groups(d).values()) {
            int state=state(c,t,group,rows,cancel);
            if(state==0){n+=group.size();eligible.add(group.get(0).uid);}else if(state==1)s+=group.size();else x+=group.size();
        }
        return new Preview(t,d,n,s,x,eligible);
    }
    private static Target fresh(Context c, Target expected, CancellationSignal cancel) throws CalendarRepository.Failure {
        Target actual=target(c,expected.id,cancel);
        if (!actual.identity.equals(expected.identity) || !actual.zone.equals(expected.zone)) throw fail("The target calendar has changed. Select the file again.");
        return actual;
    }
    private static final String[] FIELDS={Events._ID,Events.CUSTOM_APP_URI,Events.TITLE,Events.DESCRIPTION,Events.EVENT_LOCATION,
        Events.DTSTART,Events.DTEND,Events.ALL_DAY,Events.EVENT_TIMEZONE,Events.EVENT_END_TIMEZONE,Events.DELETED,Events.STATUS,
        Events.RRULE,Events.RDATE,Events.EXDATE,Events.EXRULE,Events.DURATION,Events.ORIGINAL_ID,Events.ORIGINAL_SYNC_ID,
        Events.HAS_ALARM,Events.HAS_ATTENDEE_DATA,Events.ACCESS_LEVEL,Events.AVAILABILITY,Events.UID_2445,
        Events.ORIGINAL_INSTANCE_TIME,Events.ORIGINAL_ALL_DAY,Events.ORGANIZER,Events.EVENT_COLOR,
        Events.GUESTS_CAN_MODIFY,Events.GUESTS_CAN_INVITE_OTHERS,Events.GUESTS_CAN_SEE_GUESTS,Events._SYNC_ID};
    private static final String[] ATTENDEE_FIELDS={CalendarContract.Attendees.ATTENDEE_NAME,CalendarContract.Attendees.ATTENDEE_EMAIL,
        CalendarContract.Attendees.ATTENDEE_IDENTITY,CalendarContract.Attendees.ATTENDEE_ID_NAMESPACE,
        CalendarContract.Attendees.ATTENDEE_TYPE,CalendarContract.Attendees.ATTENDEE_STATUS,CalendarContract.Attendees.ATTENDEE_RELATIONSHIP};
    private static final Map<String,String> NATIVE_FIELDS;
    static {
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("X-ANDROID-EVENT-COLOR",Events.EVENT_COLOR);
        fields.put("X-ANDROID-GUESTS-CAN-MODIFY",Events.GUESTS_CAN_MODIFY);
        fields.put("X-ANDROID-GUESTS-CAN-INVITE-OTHERS",Events.GUESTS_CAN_INVITE_OTHERS);
        fields.put("X-ANDROID-GUESTS-CAN-SEE-GUESTS",Events.GUESTS_CAN_SEE_GUESTS);
        NATIVE_FIELDS=Collections.unmodifiableMap(fields);
    }
    private static ContentValues capture(Cursor row,String[] fields) {
        ContentValues v=new ContentValues();
        for(int i=0;i<fields.length;i++) {
            if(row.isNull(i))v.putNull(fields[i]);
            else if(row.getType(i)==Cursor.FIELD_TYPE_INTEGER)v.put(fields[i],row.getLong(i));
            else v.put(fields[i],row.getString(i));
        }
        return v;
    }
    private static final class Rows {
        final Map<String,List<ContentValues>> marked=new HashMap<>();
        final Map<Long,ContentValues> all=new HashMap<>();
        final Map<String,List<ContentValues>> existing=new HashMap<>();
        final Map<Long,List<ContentValues>> linked=new HashMap<>();
        final Set<Long> invalidLinks=new HashSet<>();
        final Set<String> blockedUids=new HashSet<>();
        boolean orphanedLink;
        int attendeeRows,attendeeChars;
    }
    private static Rows rows(Context c,Target t,Icalendar.Document document,CancellationSignal cancel) throws CalendarRepository.Failure {
        Rows result=new Rows();int count=0,chars=0;
        Set<String> wanted=new HashSet<>(),uids=new HashSet<>();Map<String,Integer> groupSizes=new HashMap<>();
        for(Icalendar.Event e:document.events){wanted.add(hash(e.sourceIdentity()));uids.add(e.uid);groupSizes.merge(e.uid,1,Integer::sum);}
        try(Cursor r=CalendarRepository.query(c,Events.CONTENT_URI,FIELDS,Events.CALENDAR_ID+"=?",new String[]{""+t.id},Events._ID+" ASC",cancel)) {
            while(r.moveToNext()) {
                if(++count>MAX_PROVIDER_ROWS)throw fail("Target exceeds the safe import scan limit (20000 events). No import started.");
                String marker=r.getString(1),sourceUid=r.getString(23);
                ContentValues v=capture(r,FIELDS);
                for(Map.Entry<String,Object> field:v.valueSet())if(field.getValue() instanceof String){int size=((String)field.getValue()).length();chars+=size;if(size>65536||chars>4*1024*1024)throw fail("Existing import records exceed the safe text scan limit. No import started.");}
                result.all.put(r.getLong(0),v);
                if(marker==null || !marker.startsWith(PREFIX))continue;
                sourceUid=nativeUid(t,v);
                if(marker.length()!=PREFIX.length()+129)throw fail("Invalid existing import identity. No import started.");
                String[] parts=marker.substring(PREFIX.length()).split("/",-1);
                if(parts.length!=2||!parts[0].matches("[0-9a-f]{64}")||!parts[1].matches("[0-9a-f]{64}"))throw fail("Invalid existing import identity. No import started.");
                if(!wanted.contains(parts[0]) && uids.contains(sourceUid))result.blockedUids.add(sourceUid);
                if(!wanted.contains(parts[0]) && r.isNull(17))continue;
                List<ContentValues> found=result.marked.computeIfAbsent(parts[0],k->new ArrayList<>());
                if(found.size()<2)found.add(v);
            }
        }
        // Compare complete connected components, not just immediate children. Even
        // an invalid exception-as-parent descendant must not disappear from a group.
        Map<Long,Long> components=new HashMap<>();Map<String,List<Long>> sync=new HashMap<>();
        for(ContentValues row:result.all.values()) {
            long id=number(row,Events._ID);components.put(id,id);
            String syncId=row.getAsString(Events._SYNC_ID);
            if(syncId!=null&&!syncId.isEmpty())sync.computeIfAbsent(syncId,k->new ArrayList<>()).add(id);
        }
        // Join duplicate sync owners once; never choose one or build quadratic edges.
        for(List<Long> ids:sync.values())for(long id:ids)join(components,ids.get(0),id);
        Set<Long> invalid=new HashSet<>();
        for(ContentValues row:result.all.values()) {
            cancel.throwIfCanceled();long id=number(row,Events._ID);Long parent=row.getAsLong(Events.ORIGINAL_ID);
            String originalSync=normal(row.getAsString(Events.ORIGINAL_SYNC_ID));
            List<Long> syncParents=sync.getOrDefault(originalSync,Collections.emptyList());
            if(parent!=null) {
                if(result.all.containsKey(parent))join(components,id,parent);
                else {invalid.add(id);result.orphanedLink=true;}
            }
            if(!originalSync.isEmpty()) {
                if(syncParents.isEmpty()){invalid.add(id);result.orphanedLink=true;}
                else {
                    join(components,id,syncParents.get(0));
                    if(syncParents.size()!=1||parent!=null&&!parent.equals(syncParents.get(0)))invalid.add(id);
                }
            }
            if(parent==null&&!originalSync.isEmpty()&&syncParents.size()==1)parent=syncParents.get(0);
            if(parent!=null) {
                ContentValues master=result.all.get(parent);
                if(master!=null&&(master.get(Events.ORIGINAL_ID)!=null||!normal(master.getAsString(Events.ORIGINAL_SYNC_ID)).isEmpty()))invalid.add(id);
            } else if(originalSync.isEmpty()&&(row.get(Events.ORIGINAL_INSTANCE_TIME)!=null||row.get(Events.ORIGINAL_ALL_DAY)!=null)) {
                invalid.add(id);result.orphanedLink=true;
            }
        }
        Map<Long,List<ContentValues>> linked=new HashMap<>();Set<Long> invalidComponents=new HashSet<>();
        for(ContentValues row:result.all.values()) {
            long id=number(row,Events._ID),component=root(components,id);
            linked.computeIfAbsent(component,k->new ArrayList<>()).add(row);
            if(invalid.contains(id))invalidComponents.add(component);
        }
        for(Map.Entry<Long,List<ContentValues>> component:linked.entrySet()) {
            Set<String> identities=new HashSet<>();boolean invalidComponent=invalidComponents.contains(component.getKey());
            for(ContentValues row:component.getValue()) {
                long id=number(row,Events._ID);identities.add(nativeUid(t,row));
                result.linked.put(id,component.getValue()); // Shared list; one component, not one copy per row/UID.
                if(invalidComponent)result.invalidLinks.add(id);
            }
            for(String uid:identities)if(uids.contains(uid)&&!result.blockedUids.contains(uid)) {
                List<ContentValues> previous=result.existing.get(uid);
                if(invalidComponent||component.getValue().size()+(previous==null?0:previous.size())>groupSizes.get(uid)) {
                    result.blockedUids.add(uid);result.existing.remove(uid);continue;
                }
                // Bound the total materialized candidate references by the input event
                // count (<=2000), even if 2000 UIDs touch one 20000-row component.
                result.existing.computeIfAbsent(uid,k->new ArrayList<>()).addAll(component.getValue());
            }
        }
        return result;
    }
    private static long root(Map<Long,Long> components,long id) {
        long head=id;while(components.get(head)!=head)head=components.get(head);
        while(id!=head){long next=components.get(id);components.put(id,head);id=next;}return head;
    }
    private static void join(Map<Long,Long> components,long a,long b) {
        long left=root(components,a),right=root(components,b);if(left!=right)components.put(Math.max(left,right),Math.min(left,right));
    }
    private static String nativeUid(Target t,ContentValues row) {
        String uid=row.getAsString(Events.UID_2445);
        return uid!=null&&!uid.isEmpty()?uid:UUID.nameUUIDFromBytes((normal(t.guard.getAsString(Calendars.NAME))+":"+t.id+":"+number(row,Events._ID)).getBytes(StandardCharsets.UTF_8))+"@blockzeit.local";
    }
    private static Map<String,List<Icalendar.Event>> groups(Icalendar.Document d) {
        Map<String,List<Icalendar.Event>> groups=new LinkedHashMap<>();
        for(Icalendar.Event e:d.events)groups.computeIfAbsent(e.uid,k->new ArrayList<>()).add(e);
        for(List<Icalendar.Event> group:groups.values())group.sort(Comparator.comparing(e->e.recurrenceId!=null));
        return groups;
    }
    private static String key(Target t,Icalendar.Event e){return t.identity+"."+hash(e.sourceIdentity());}
    private static String marker(Icalendar.Event e){return PREFIX+hash(e.sourceIdentity())+"/"+e.fingerprint();}
    private static SharedPreferences journal(Context c){return c.getSharedPreferences("calendar-ics-import-v1",Context.MODE_PRIVATE);}
    private static String groupKey(Target t,List<Icalendar.Event> group){return t.identity+".group."+hash(group.get(0).uid);}
    private static String groupMarker(List<Icalendar.Event> group){List<String> markers=new ArrayList<>();for(Icalendar.Event e:group)markers.add(marker(e));Collections.sort(markers);return hash(String.join("\n",markers));}
    private static List<String> expectedAttendees(Icalendar.Event event) {
        List<String> expected=new ArrayList<>();for(Icalendar.Attendee attendee:event.attendees)expected.add(attendee.canonical());return expected;
    }
    private static List<ContentValues> attendeeRows(Context c,long id,Rows rows,CancellationSignal cancel)throws CalendarRepository.Failure {
        List<ContentValues> actual=new ArrayList<>();int chars=0;
        try(Cursor row=CalendarRepository.query(c,CalendarContract.Attendees.CONTENT_URI,ATTENDEE_FIELDS,
                CalendarContract.Attendees.EVENT_ID+"=?",new String[]{""+id},null,cancel)) {
            while(row.moveToNext()) {
                if(++rows.attendeeRows>20000)throw fail("Existing attendees exceed the safe total scan limit (20000).");
                if(actual.size()>=1000)throw fail("Existing attendees exceed the safe per-event scan limit (1000).");
                for(int i=0;i<ATTENDEE_FIELDS.length;i++) {
                    String value=i<4?normal(row.getString(i)):""+(row.isNull(i)?0:row.getInt(i));
                    chars+=value.length();rows.attendeeChars+=value.length();
                    if(value.length()>65536||chars>1024*1024||rows.attendeeChars>4*1024*1024)throw fail("Existing attendee metadata exceeds the safe scan limit.");
                }
                actual.add(capture(row,ATTENDEE_FIELDS));
            }
        }
        return actual;
    }
    private static List<String> actualAttendees(Context c,long id,Rows rows,CancellationSignal cancel)throws CalendarRepository.Failure {
        List<String> actual=new ArrayList<>();
        for(ContentValues row:attendeeRows(c,id,rows,cancel)) {
            StringBuilder canonical=new StringBuilder();
            for(int i=0;i<ATTENDEE_FIELDS.length;i++) {
                String value=i<4?normal(row.getAsString(ATTENDEE_FIELDS[i])):""+number(row,ATTENDEE_FIELDS[i]);
                canonical.append(value.length()).append(':').append(value);
            }
            actual.add(canonical.toString());
        }
        Collections.sort(actual);return actual;
    }
    private static int existingState(Context c,Target t,List<Icalendar.Event> group,List<ContentValues> candidates,Rows rows,CancellationSignal cancel)throws CalendarRepository.Failure {
        // A journal is evidence of a prior insert, not permission to adopt a stripped marker.
        if(candidates.size()!=group.size()||journal(c).contains(groupKey(t,group)))return 2;
        for(Icalendar.Event event:group)if(journal(c).contains(key(t,event))||rows.marked.containsKey(hash(event.sourceIdentity())))return 2;
        Map<Long,List<ContentValues>> guests=new HashMap<>();
        for(ContentValues row:candidates) {
            String marker=row.getAsString(Events.CUSTOM_APP_URI);
            if(marker!=null&&marker.startsWith(PREFIX)||number(row,Events.DELETED)!=0)return 2;
            guests.put(number(row,Events._ID),attendeeRows(c,number(row,Events._ID),rows,cancel));
        }
        // Serialize exactly the supported native export semantics, including exact native
        // durations, default/null metadata and master-derived exception identities. Nothing
        // is written, marked, adopted or read from Reminders/preferences by normalization.
        Icalendar.Document nativeDocument;
        try {nativeDocument=CalendarExport.normalizeForImport(t.guard,candidates,guests,ZoneId.of(t.zone),cancel);}
        catch(CalendarRepository.Failure|IllegalArgumentException|ArithmeticException|java.time.DateTimeException invalid){return 2;}
        if(!nativeDocument.rejected.isEmpty()||nativeDocument.events.size()!=group.size())return 2;
        Map<String,String> expected=new HashMap<>();
        for(Icalendar.Event event:group)expected.put(event.sourceIdentity(),event.fingerprint());
        for(Icalendar.Event event:nativeDocument.events)if(!Objects.equals(expected.remove(event.sourceIdentity()),event.fingerprint()))return 2;
        return expected.isEmpty()?1:2;
    }
    // 0 entirely new UID group, 1 entirely identical, 2 conflict (including partial/missing/foreign groups).
    private static int state(Context c,Target t,List<Icalendar.Event> group,Rows rows,CancellationSignal cancel)throws CalendarRepository.Failure {
        if(rows.blockedUids.contains(group.get(0).uid))return 2;
        List<ContentValues> candidates=rows.existing.get(group.get(0).uid);
        if(candidates!=null)for(ContentValues row:candidates) {
            String retained=row.getAsString(Events.CUSTOM_APP_URI);
            if(retained==null||!retained.startsWith(PREFIX))return existingState(c,t,group,candidates,rows,cancel);
        }
        String committedGroup=journal(c).getString(groupKey(t,group),null);
        if(committedGroup!=null&&!committedGroup.equals(groupMarker(group)))return 2;
        int fresh=0,identical=0;Long masterId=null;Set<Long> expectedIds=new HashSet<>();
        for(Icalendar.Event e:group) {
            List<ContentValues> found=rows.marked.get(hash(e.sourceIdentity()));
            if(found==null||found.isEmpty()) {if(journal(c).contains(key(t,e)))return 2;fresh++;continue;}
            if(found.size()!=1)return 2;
            ContentValues v=found.get(0);
            if(rows.invalidLinks.contains(number(v,Events._ID)))return 2;
            String retainedMarker=v.getAsString(Events.CUSTOM_APP_URI);
            String expectedMarker=marker(e);
            String legacyMarker=PREFIX+hash(e.sourceIdentity())+"/"+e.legacyFingerprint();
            boolean legacy=committedGroup==null && e.legacyCompatible() && legacyMarker.equals(retainedMarker)
                    && legacyMarker.equals(journal(c).getString(key(t,e),null));
            if(!legacy && !expectedMarker.equals(journal(c).getString(key(t,e),null)))return 2;long id=number(v,Events._ID);expectedIds.add(id);
            if(e.recurrenceId==null){masterId=id;if(group.size()>1&&!seriesSyncId(t,e.uid).equals(v.getAsString(Events._SYNC_ID)))return 2;}
            if(!legacy && !expectedMarker.equals(retainedMarker) || number(v,Events.DELETED)!=0)return 2;
            ContentValues expected=values(t,e);
            if(legacy){if(e.access==0)expected.put(Events.ACCESS_LEVEL,Events.ACCESS_PUBLIC);if(e.status==-1)expected.put(Events.STATUS,Events.STATUS_CONFIRMED);}
            for(String field:new String[]{Events.TITLE,Events.DESCRIPTION,Events.EVENT_LOCATION,Events.DTSTART,Events.DTEND,Events.ALL_DAY,
                    Events.EVENT_TIMEZONE,Events.ACCESS_LEVEL,Events.AVAILABILITY,Events.STATUS,Events.RRULE,Events.RDATE,Events.EXDATE,Events.DURATION})
                if(!Objects.equals(normal(v.getAsString(field)),normal(expected.getAsString(field))))return 2;
            String endZone=v.getAsString(Events.EVENT_END_TIMEZONE);
            if(endZone!=null&&!endZone.isEmpty()&&!endZone.equals(e.endZone)||!e.endZone.equals(e.zone)&&!e.endZone.equals(endZone))return 2;
            // Original generic imports did not persist UID/organizer; retain those identities safely.
            String uid=v.getAsString(Events.UID_2445);if(uid!=null&&!uid.isEmpty()&&!uid.equals(e.uid))return 2;
            String actualOrganizer=normal(v.getAsString(Events.ORGANIZER));
            if(!actualOrganizer.equals(e.organizer) && !(committedGroup==null && e.organizer.isEmpty()
                    && actualOrganizer.equals(normal(t.guard.getAsString(Calendars.OWNER_ACCOUNT)))))return 2;
            for(Map.Entry<String,String> field:NATIVE_FIELDS.entrySet()) {
                Integer expectedNative=e.nativeProperties.get(field.getKey()),actualNative=v.getAsInteger(field.getValue());
                if(expectedNative!=null) {
                    if(actualNative==null && legacy && !field.getKey().equals("X-ANDROID-EVENT-COLOR"))continue;
                    if(!expectedNative.equals(actualNative))return 2;
                }
                else if(actualNative!=null) {
                    int nativeDefault=field.getKey().equals("X-ANDROID-GUESTS-CAN-MODIFY")?0:1;
                    if(field.getKey().equals("X-ANDROID-EVENT-COLOR")||actualNative!=nativeDefault)return 2;
                }
            }
            if(e.recurrenceId==null) {
                if(v.getAsString(Events.ORIGINAL_ID)!=null||v.getAsString(Events.ORIGINAL_SYNC_ID)!=null||v.getAsString(Events.ORIGINAL_INSTANCE_TIME)!=null)return 2;
            } else if(!Objects.equals(masterId,v.getAsLong(Events.ORIGINAL_ID)) || !e.recurrenceId.equals(v.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
                    || number(v,Events.ORIGINAL_ALL_DAY)!=(e.recurrenceAllDay?1:0)
                    || !seriesSyncId(t,e.uid).equals(v.getAsString(Events.ORIGINAL_SYNC_ID)))return 2;
            if(!normal(v.getAsString(Events.EXRULE)).isEmpty())return 2;
            // HAS_ATTENDEE_DATA is not a reliable existence index: normal attendee inserts can
            // leave it unset. Always compare actual attendee rows, including unexpected additions.
            if(!expectedAttendees(e).equals(actualAttendees(c,id,rows,cancel)))return 2;
            identical++;
        }
        if(candidates!=null)for(ContentValues row:candidates)if(!expectedIds.contains(row.getAsLong(Events._ID)))return 2;
        // Marker/journal identity can legitimately predate native UID storage. Link
        // closure must therefore also be checked through the matched row IDs.
        for(long id:expectedIds) {
            List<ContentValues> component=rows.linked.getOrDefault(id,Collections.emptyList());
            if(component.size()>expectedIds.size())return 2;
            for(ContentValues row:component)if(!expectedIds.contains(row.getAsLong(Events._ID)))return 2;
        }
        if(masterId!=null)for(ContentValues row:rows.all.values())if(masterId.equals(row.getAsLong(Events.ORIGINAL_ID))&&!expectedIds.contains(row.getAsLong(Events._ID)))return 2;
        if(fresh==group.size())return committedGroup==null&&!rows.orphanedLink?0:2;
        return identical==group.size()?1:2;
    }
    private static final class Batch {
        final ArrayList<ContentProviderOperation> operations;final List<Integer> events;
        Batch(ArrayList<ContentProviderOperation> operations,List<Integer> events){this.operations=operations;this.events=events;}
    }
    // Provider-local linkage is separate from the source UID. API34 expands exceptions
    // through sync IDs, even when their ORIGINAL_ID backreference is retained.
    private static String seriesSyncId(Target t,String uid){return PREFIX+"series/"+t.identity+"/"+hash(uid);}
    private static Batch batch(Target t,List<Icalendar.Event> group)throws CalendarRepository.Failure {
        ArrayList<ContentProviderOperation> ops=new ArrayList<>();
        ops.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(Calendars.CONTENT_URI,t.id)).withValues(t.guard).withExpectedCount(1).build());
        StringBuilder absent=new StringBuilder(Events.CALENDAR_ID+"=? AND ("+Events.UID_2445+"=?");
        List<String> absenceArgs=new ArrayList<>();absenceArgs.add(""+t.id);absenceArgs.add(group.get(0).uid);
        String syncId=group.size()>1?seriesSyncId(t,group.get(0).uid):null;
        if(syncId!=null){absent.append(" OR ").append(Events._SYNC_ID).append("=? OR ").append(Events.ORIGINAL_SYNC_ID).append("=?");absenceArgs.add(syncId);absenceArgs.add(syncId);}
        for(Icalendar.Event event:group){absent.append(" OR ").append(Events.CUSTOM_APP_URI).append(" LIKE ?");absenceArgs.add(PREFIX+hash(event.sourceIdentity())+"/%");}
        absent.append(')');
        Uri nativeUri=Events.CONTENT_URI;
        if(syncId!=null) {
            // This operation-local route permits the new master's sync-only ID without
            // changing accounts/configuration. Its absence query must also see shadow
            // rows: provider master insertion can backfill their ORIGINAL_ID too.
            nativeUri=nativeUri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME,t.guard.getAsString(Calendars.ACCOUNT_NAME))
                .appendQueryParameter(Calendars.ACCOUNT_TYPE,t.guard.getAsString(Calendars.ACCOUNT_TYPE)).build();
        }
        ops.add(ContentProviderOperation.newAssertQuery(nativeUri).withSelection(absent.toString(),absenceArgs.toArray(new String[0])).withExpectedCount(0).build());
        int masterOperation=-1;List<Integer> eventOperations=new ArrayList<>();
        for(Icalendar.Event event:group) {
            int operation=ops.size();Uri insertUri=event.recurrenceId==null?nativeUri:Events.CONTENT_URI;
            ContentProviderOperation.Builder insert=ContentProviderOperation.newInsert(insertUri).withValues(values(t,event));
            if(event.recurrenceId==null){masterOperation=operation;if(syncId!=null)insert.withValue(Events._SYNC_ID,syncId);}
            else {if(masterOperation<0)throw fail("Exception has no confirmed master.");insert.withValue(Events.ORIGINAL_SYNC_ID,syncId).withValueBackReference(Events.ORIGINAL_ID,masterOperation);}
            ops.add(insert.build());eventOperations.add(operation);
            for(Icalendar.Attendee attendee:event.attendees)ops.add(ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
                .withValues(attendeeValues(attendee)).withValueBackReference(CalendarContract.Attendees.EVENT_ID,operation).build());
        }
        // This is below Binder's transaction limit, including operation overhead. Never split a UID group.
        android.os.Parcel parcel=android.os.Parcel.obtain();try{parcel.writeTypedList(ops);if(parcel.dataSize()>512*1024)throw fail("UID group exceeds the safe atomic import byte limit.");}finally{parcel.recycle();}
        return new Batch(ops,eventOperations);
    }
    static Result insert(Context c,Preview preview,CancellationSignal cancel)throws CalendarRepository.Failure {
        Target t=preview.target;fresh(c,t,cancel);Rows actual=rows(c,t,preview.document,cancel);Result result=new Result();boolean attempted=false;
        try {
            for(List<Icalendar.Event> group:groups(preview.document).values()) {
                cancel.throwIfCanceled();int state=state(c,t,group,actual,cancel);
                if(state==0&&!preview.eligibleUids.contains(group.get(0).uid)){result.conflicts+=group.size();continue;}
                if(state==1){result.skipped+=group.size();continue;}if(state==2){result.conflicts+=group.size();continue;}
                Target guarded=fresh(c,t,cancel);Batch batch=batch(guarded,group);
                ArrayList<ContentProviderOperation> ops=batch.operations;List<Integer> eventOperations=batch.events;
                SharedPreferences.Editor intent=journal(c).edit().putString(groupKey(t,group),groupMarker(group));
                for(Icalendar.Event event:group) {
                    intent.putString(key(t,event),marker(event));
                    if(event.archivedEnd!=null)intent.putString(key(t,event)+".archive",event.archivedEnd+"\n"+event.archivedEndZone);
                }
                if(!intent.commit())throw fail("Could not persist import identity; no further events inserted.");
                cancel.throwIfCanceled();attempted=true;
                ContentProviderResult[] reply=c.getContentResolver().applyBatch(CalendarContract.AUTHORITY,ops);
                if(reply==null||reply.length!=ops.size())throw fail("Provider returned an unclear group insert result.");
                for(int operation:eventOperations)if(reply[operation].uri==null||ContentUris.parseId(reply[operation].uri)<=0)throw fail("Provider returned an unclear event insert result.");
                // Verify every retained event and attendee against actual rows. An ambiguous batch keeps its
                // intent journal and becomes a conflict, never a second attempt to insert a partial series.
                for(int operation:eventOperations) {
                    long insertedId=ContentUris.parseId(reply[operation].uri);
                    try(Cursor retained=CalendarRepository.query(c,reply[operation].uri,FIELDS,Events.CALENDAR_ID+"=?",new String[]{""+t.id},null,cancel)) {
                        if(!retained.moveToFirst())throw fail("Inserted event could not be verified.");
                        ContentValues verified=capture(retained,FIELDS);String retainedMarker=verified.getAsString(Events.CUSTOM_APP_URI);
                        if(retainedMarker==null||!retainedMarker.startsWith(PREFIX))throw fail("Provider did not retain import identity.");
                        String source=retainedMarker.substring(PREFIX.length()).split("/",-1)[0];
                        actual.marked.computeIfAbsent(source,k->new ArrayList<>()).add(verified);actual.all.put(insertedId,verified);
                    }
                }
                if(state(c,t,group,actual,cancel)!=1)throw fail("Provider did not retain the imported group/identity as expected.");
                result.inserted+=group.size();
            }
        } catch(CalendarRepository.Failure|android.os.RemoteException|OperationApplicationException|RuntimeException failure) {
            result.failure="Import stopped; completed groups remain. Select the file again to recheck. Missing or uncertain identities are conflicts, never reinserted automatically.";
        } finally {
            if(attempted) {
                CalendarRefresh.invalidate(t.id);Context app=c.getApplicationContext();new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                    for(int widget:BlockzeitWidget.ids(app))if(Store.calendarId(app,widget)==t.id){CalendarRefresh.request(app,widget);BlockzeitWidget.render(app,widget);}
                });
            }
        }
        return result;
    }
    private static ContentValues attendeeValues(Icalendar.Attendee a) {
        ContentValues v=new ContentValues();v.put(CalendarContract.Attendees.ATTENDEE_NAME,a.name);v.put(CalendarContract.Attendees.ATTENDEE_EMAIL,a.email);
        v.put(CalendarContract.Attendees.ATTENDEE_IDENTITY,a.identity);v.put(CalendarContract.Attendees.ATTENDEE_ID_NAMESPACE,a.namespace);
        v.put(CalendarContract.Attendees.ATTENDEE_TYPE,a.type);v.put(CalendarContract.Attendees.ATTENDEE_STATUS,a.status);v.put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,a.relationship);return v;
    }
    private static ContentValues values(Target t,Icalendar.Event e) {
        ContentValues v=new ContentValues();v.put(Events.CALENDAR_ID,t.id);v.put(Events.CUSTOM_APP_URI,marker(e));v.put(Events.UID_2445,e.uid);
        v.put(Events.TITLE,e.title);v.put(Events.DESCRIPTION,e.description);v.put(Events.EVENT_LOCATION,e.location);v.put(Events.DTSTART,e.start);
        if(e.recurring()) {
            v.put(Events.DURATION,e.duration);if(!e.rrule.isEmpty())v.put(Events.RRULE,e.rrule);if(!e.rdate.isEmpty())v.put(Events.RDATE,e.rdate);if(!e.exdate.isEmpty())v.put(Events.EXDATE,e.exdate);
        } else v.put(Events.DTEND,e.end);
        v.put(Events.ALL_DAY,e.allDay?1:0);v.put(Events.EVENT_TIMEZONE,e.zone);v.put(Events.EVENT_END_TIMEZONE,e.endZone);
        if(e.status>=0)v.put(Events.STATUS,e.status);
        v.put(Events.ACCESS_LEVEL,e.access);v.put(Events.AVAILABILITY,e.availability);
        // Suppress the provider's auto-generated owner organizer when the source has no organizer.
        v.put(Events.ORGANIZER,e.organizer);
        for(Map.Entry<String,Integer> field:e.nativeProperties.entrySet())v.put(NATIVE_FIELDS.get(field.getKey()),field.getValue());
        if(!e.attendees.isEmpty())v.put(Events.HAS_ATTENDEE_DATA,1);
        if(e.recurrenceId!=null){v.put(Events.ORIGINAL_INSTANCE_TIME,e.recurrenceId);v.put(Events.ORIGINAL_ALL_DAY,e.recurrenceAllDay?1:0);}
        return v;
    }
    private static long number(ContentValues v,String k){Long n=v.getAsLong(k);return n==null?0:n;}
    private static String normal(String s){return s==null?"":s;}
    static String hash(String s){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String safe(String s){return s==null?"Cannot read file.":s.length()>200?s.substring(0,200):s;}
    private static CalendarRepository.Failure fail(String s){return new CalendarRepository.Failure(s);}
}
