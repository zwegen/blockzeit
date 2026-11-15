package de.blockzeit;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.function.*;

/** File picker and explicit preview/confirm UI, confined to the existing Calendar settings. */
final class CalendarImportSettings {
    static final int PICK=212, PERMISSION=213;
    private final SettingsActivity activity;
    private final LongSupplier calendar;
    private final IntSupplier widget;
    private final Button button;
    private final TextView status;
    private CalendarRepository.Request<?> request;
    private AlertDialog dialog;
    private boolean closed, picking, busy, restorePreview;
    private long target=-1; private int targetWidget;
    private Uri file;
    CalendarImportSettings(SettingsActivity a, LinearLayout layout, LongSupplier calendar, IntSupplier widget) {
        activity=a;this.calendar=calendar;this.widget=widget;
        button=new Button(a);button.setText("Import .ics / .ical file…");SettingsControls.button(button);SettingsControls.add(layout,button);
        status=new TextView(a);status.setTextSize(15);SettingsControls.foreground(status);status.setVisibility(View.GONE);layout.addView(status);
        status.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence text,int start,int before,int count) {
                status.setVisibility(text.length()==0?View.GONE:View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        button.setOnClickListener(v->start());
    }
    Button importButton() { return button; }
    private boolean current(){return !closed && widget.getAsInt()==targetWidget && calendar.getAsLong()==target;}
    private void start(){
        if(busy || picking || closed)return;
        target=calendar.getAsLong();targetWidget=widget.getAsInt();file=null;
        if(target<0){status.setText("Choose an existing local calendar or create one first. The current selection works before Save.");return;}
        if(!CalendarRepository.canRead(activity)||!CalendarRepository.canWrite(activity)) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR},PERMISSION);return;
        }
        pick();
    }
    void permissionResult(int code){
        if(code!=PERMISSION)return;
        if(current() && CalendarRepository.canRead(activity)&&CalendarRepository.canWrite(activity))pick();
        else status.setText("Import requires both calendar permissions. Tap Import to retry.");
    }
    private void pick(){
        long id=target;
        run((app,cancel)->CalendarImport.target(app,id,cancel),t->{
            if(!current())return;
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{picking=true;activity.startActivityForResult(intent,PICK);status.setText("Choose an iCalendar file. No subscription or network sync is added.");}
            catch(android.content.ActivityNotFoundException missing){picking=false;status.setText("No document picker is available.");}
        });
    }
    void activityResult(int code,int result,Intent data){
        if(code!=PICK)return;picking=false;
        if(result!=android.app.Activity.RESULT_OK || data==null || data.getData()==null){status.setText("File selection cancelled. Nothing imported.");return;}
        if(!current()){status.setText("Calendar selection changed. Select the file again.");return;}
        file=data.getData();
        if(!"content".equals(file.getScheme())){file=null;status.setText("Choose a document from Android's file picker.");return;}
        try{
            if((data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)
                activity.getContentResolver().takePersistableUriPermission(file,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException ignored){/* Temporary grant still works; restoration may require repicking. */}
        preview();
    }
    private void preview(){
        if(file==null || !current() || busy)return;
        long id=target;Uri uri=file;
        run((app,cancel)->CalendarImport.read(app,id,uri,cancel),value->{
            if(!current())return;
            StringBuilder summary=new StringBuilder("Target: ").append(value.target.name).append(" · ").append(target)
                .append("\nEvents: ").append(value.document.totalCount).append("\nNew: ").append(value.fresh)
                .append("\nIdentical / skipped: ").append(value.skipped).append("\nConflicts (not overwritten): ").append(value.conflicts)
                .append("\nUnsupported / rejected: ").append(Math.max(value.document.rejected.size(), value.document.totalCount-value.document.events.size()));
            for(int i=0;i<Math.min(8,value.document.rejected.size());i++)summary.append("\n• ").append(value.document.rejected.get(i));
            summary.append("\n\nOnly supported new events are inserted. Existing events are never changed or deleted. This persists even if settings are cancelled.");
            AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("Import preview")
                .setMessage(summary.toString()).setNegativeButton(android.R.string.cancel,(d,w)->{file=null;status.setText("Import cancelled. Nothing imported.");});
            if(value.fresh>0)builder.setPositiveButton("Import "+value.fresh+" new",(d,w)->{
                if(!current()){status.setText("Calendar selection changed. Select the file again.");return;}
                // Immutable preview only: worker never retains this controller or Activity.
                file=null;
                run((app,cancel)->CalendarImport.insert(app,value,cancel),done->{
                    status.setText("Inserted: "+done.inserted+" · skipped: "+done.skipped+" · conflicts: "+done.conflicts
                        +(done.failure==null?"":"\n"+done.failure));
                });
            });
            dialog=builder.show();
            OutsideCancel.negative(dialog);
        });
    }
    private <T> void run(CalendarRepository.Work<T> work,Consumer<T> done){
        if(closed||busy)return;busy=true;button.setEnabled(false);status.setText("Checking calendar / file…");
        request=CalendarRepository.enqueue(activity,work,(value,failure)->{
            request=null;if(closed)return;busy=false;button.setEnabled(true);
            if(failure==null)done.accept(value);else status.setText(failure.getMessage());
        });
        if(request==null){busy=false;button.setEnabled(true);status.setText("Calendar storage busy. Try again.");}
    }
    void saveState(Bundle state){
        state.putLong("icsTarget",target);state.putInt("icsWidget",targetWidget);state.putBoolean("icsPicking",picking);
        state.putString("icsFile",file==null?null:file.toString());
        state.putBoolean("icsInterrupted",busy && file==null);
    }
    void restoreState(Bundle state){
        if(state==null)return;target=state.getLong("icsTarget",-1);targetWidget=state.getInt("icsWidget",0);picking=state.getBoolean("icsPicking",false);
        String uri=state.getString("icsFile");if(uri!=null){file=Uri.parse(uri);restorePreview=true;}
        if(state.getBoolean("icsInterrupted",false))status.setText("A previous import/check was interrupted. Select the file again to recheck completed rows safely.");
    }
    void resume(){if(restorePreview){restorePreview=false;if(current())preview();else{file=null;status.setText("Calendar selection changed. Select the file again.");}}}
    void selectionChanged(){
        if(targetWidget==0||current())return;
        if(dialog!=null){dialog.dismiss();dialog=null;}
        file=null;restorePreview=false;
    }
    boolean blocksOutsideCancel(){return busy || picking;}
    void close(){closed=true;if(request!=null){request.cancel();request=null;}if(dialog!=null){dialog.dismiss();dialog=null;}}
}
