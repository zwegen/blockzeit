package de.blockzeit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.function.LongSupplier;

/** Export the explicitly selected calendar; never changes provider rows or widget preferences. */
final class CalendarExportSettings {
    static final int CREATE=214, PERMISSION=216;
    private final SettingsActivity activity;
    private final LongSupplier calendar;
    private final Button button;
    private AlertDialog dialog;
    private CalendarRepository.Request<?> request;
    private CalendarExportFiles.Request<?> fileRequest;
    private boolean busy, closed, picking, permissionPending;
    private long target=-1;
    CalendarExportSettings(SettingsActivity activity,LinearLayout layout,LongSupplier calendar){
        this.activity=activity;this.calendar=calendar;
        button=new Button(activity);button.setText("Export .ics file…");SettingsControls.button(button);SettingsControls.add(layout,button);
        button.setOnClickListener(v->start());
    }
    Button exportButton(){return button;}
    private void start(){
        if(busy||picking||closed)return;
        target=calendar.getAsLong();
        if(target<0){message("Choose a calendar first. The current selection works before Save.");return;}
        if(!CalendarRepository.canRead(activity)){
            permissionPending=true;activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR},PERMISSION);return;
        }
        pick();
    }
    void permissionResult(int code){
        if(code!=PERMISSION||!permissionPending||closed)return;permissionPending=false;
        if(CalendarRepository.canRead(activity))pick();else message("Export requires calendar read access. Tap Export to retry.");
    }
    private void pick(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/calendar").addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_TITLE,"Blockzeit-calendar.ics");
        try{picking=true;activity.startActivityForResult(intent,CREATE);}
        catch(android.content.ActivityNotFoundException missing){picking=false;message("No document picker is available. Nothing exported.");}
    }
    void activityResult(int code,int result,Intent data){
        if(code!=CREATE||!picking||closed)return;picking=false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null){message("File selection cancelled. Nothing exported.");return;}
        Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){message("Choose a document from Android's file picker.");return;}
        if(target<0){message("Export interrupted. Choose the calendar again.");return;}
        busy=true;button.setEnabled(false);
        progress("Reading calendar…",true);long id=target;
        request=CalendarRepository.enqueue(activity,(app,cancel)->CalendarExport.export(app,id,cancel),(bytes,failure)->{
            request=null;if(closed)return;dismiss();
            if(failure!=null){finish(CalendarExport.error(failure)+" Nothing exported; discard any empty new document.");return;}
            write(uri,bytes);
        });
        if(request==null)finish("Calendar storage busy. Nothing exported; discard any empty new document.");
    }
    private void progress(String text){progress(text,false);}
    private void progress(String text,boolean readOnly){
        dialog=new AlertDialog.Builder(activity).setTitle("Calendar export").setMessage(text).setCancelable(false)
            .setNegativeButton(android.R.string.cancel,(d,w)->{
                if(request!=null){request.cancel();request=null;}
                if(fileRequest!=null){fileRequest.cancel();fileRequest=null;}
                busy=false;button.setEnabled(true);message("Export cancelled. Discard any incomplete new document; calendar data is unchanged.");
            }).show();
        if(readOnly)OutsideCancel.negative(dialog);
    }
    private void write(Uri uri,byte[] bytes){
        progress("Writing iCalendar file…");
        fileRequest=CalendarExportFiles.start(activity,(app,io)->{io.write(uri,bytes);return bytes.length;},(size,failure)->{
            fileRequest=null;if(closed)return;
            finish(failure==null?"Calendar exported ("+size+" bytes). Includes events and series, without reminders or app settings.":failure.getMessage());
        });
        if(fileRequest==null)finish("File access busy. Nothing exported; discard any empty new document.");
    }
    private void finish(String text){busy=false;button.setEnabled(true);dismiss();message(text);}
    private void dismiss(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    private void message(String text){if(!closed){dismiss();dialog=new AlertDialog.Builder(activity).setTitle("Calendar export")
        .setMessage(text).setPositiveButton(android.R.string.ok,null).show();
        OutsideCancel.dialog(dialog, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());}}
    void saveState(Bundle state){state.putLong("exportTarget",target);state.putBoolean("exportPicking",picking);state.putBoolean("exportPermission",permissionPending);state.putBoolean("exportInterrupted",busy);}
    void restoreState(Bundle state){if(state!=null){target=state.getLong("exportTarget",-1);picking=state.getBoolean("exportPicking",false);permissionPending=state.getBoolean("exportPermission",false);if(state.getBoolean("exportInterrupted",false))message("Previous export interrupted. Discard any incomplete new document and export again.");}}
    boolean blocksOutsideCancel(){return busy || picking;}
    void close(){closed=true;if(request!=null)request.cancel();if(fileRequest!=null)fileRequest.cancel();dismiss();}
}
