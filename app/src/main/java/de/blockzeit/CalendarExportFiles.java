package de.blockzeit;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreign SAF IO must never occupy CalendarRepository's provider lane.
 * One admitted file request, one IO thread and one cancellation/close thread, bounded queues.
 * A stuck/hostile provider can retain this slot, but cannot grow threads/tasks or block calendars.
 */
final class CalendarExportFiles {
    static final long TIMEOUT_MS=15000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor IO=executor("Blockzeit-export-file");
    private static final ThreadPoolExecutor CLOSER=executor("Blockzeit-export-close");
    private static Request<?> active;
    private static ThreadPoolExecutor executor(String name){
        ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{
            Thread thread=new Thread(r,name);thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());pool.allowCoreThreadTimeOut(true);return pool;
    }
    interface Work<T>{T run(Context app,Request<T> request)throws Exception;}
    static synchronized boolean idle(){return active==null;}
    static synchronized <T>Request<T> start(Context app,Work<T> work,CalendarRepository.Reply<T> reply){return start(app,work,reply,TIMEOUT_MS);}
    static synchronized <T>Request<T> start(Context app,Work<T> work,CalendarRepository.Reply<T> reply,long timeout){
        if(active!=null)return null;
        Request<T> request=new Request<>(app,work,reply);active=request;
        MAIN.postDelayed(request.deadline,timeout);
        try{IO.execute(request);return request;}
        catch(RejectedExecutionException busy){active=null;MAIN.removeCallbacks(request.deadline);return null;}
    }
    private static synchronized void release(Request<?> request){if(active==request)active=null;}
    static final class Request<T> implements Runnable {
        private final Context app;
        // This signal is local only: no remote cancellation callback can block the UI.
        final CancellationSignal signal=new CancellationSignal();
        private final CancellationSignal openSignal=new CancellationSignal();
        private Work<T> work;
        private CalendarRepository.Reply<T> reply;
        private final List<Closeable> resources=new ArrayList<>();
        private volatile boolean stopped;
        private boolean workerDone,cleanupPending;
        private Thread runner;
        private T value;private CalendarRepository.Failure failure;
        private final Runnable publication=this::publish;
        private final Runnable deadline=()->stop(true);
        Request(Context app,Work<T> work,CalendarRepository.Reply<T> reply){this.app=app.getApplicationContext();this.work=work;this.reply=reply;}
        void check(){if(stopped)throw new OperationCanceledException();signal.throwIfCanceled();}
        <C extends Closeable>C track(C resource){
            synchronized(this){resources.add(resource);}check();return resource;
        }
        InputStream read(Uri uri)throws IOException{
            check();AssetFileDescriptor descriptor=app.getContentResolver().openAssetFileDescriptor(uri,"r",openSignal);
            if(descriptor==null)throw new IOException();track(descriptor);return track(descriptor.createInputStream());
        }
        void write(Uri uri,byte[] bytes)throws IOException{
            if(bytes==null||bytes.length==0)throw new IOException("Empty export");
            check();AssetFileDescriptor descriptor=app.getContentResolver().openAssetFileDescriptor(uri,"wt",openSignal);
            if(descriptor==null)throw new IOException();track(descriptor);
            // AssetFileDescriptor's bounded stream silently discards bytes beyond its length.
            // SAF export must be a complete document, never an asset slice or truncated success.
            if(descriptor.getStartOffset()!=0 || descriptor.getDeclaredLength()>=0&&descriptor.getDeclaredLength()<bytes.length)
                throw new IOException("Document descriptor cannot hold the complete export");
            OutputStream output=track(descriptor.createOutputStream());
            for(int offset=0;offset<bytes.length;offset+=4096){check();output.write(bytes,offset,Math.min(4096,bytes.length-offset));}
            check();output.flush();check();
        }
        private IOException closeResources(boolean abort){
            List<Closeable> closing;
            synchronized(this){closing=new ArrayList<>(resources);resources.clear();}
            IOException error=null;
            for(int step=0;step<closing.size();step++)try{closing.get(abort?step:closing.size()-1-step).close();}
                catch(IOException|RuntimeException failed){error=new IOException("File close failed");}
            return error;
        }
        private void releaseIfDone(){boolean done;synchronized(this){done=workerDone&&!cleanupPending;}if(done)release(this);}
        @Override public void run(){
            T result=null;CalendarRepository.Failure error=null;
            try{
                Work<T> operation;synchronized(this){runner=Thread.currentThread();operation=work;work=null;}
                check();result=operation.run(app,this);check();
            }catch(CalendarRepository.Failure invalid){error=invalid;}
            catch(Exception unavailable){error=new CalendarRepository.Failure("File access failed. No calendar data changed; discard any incomplete new export file.");}
            finally{
                IOException closing=closeResources(false);
                if(closing!=null)error=new CalendarRepository.Failure("Export file could not be closed completely. Discard any incomplete new file; calendar data is unchanged.");
                MAIN.removeCallbacks(deadline);
                synchronized(this){runner=null;workerDone=true;if(!stopped){value=result;failure=error;MAIN.post(publication);}}
                releaseIfDone();
            }
        }
        private void publish(){
            CalendarRepository.Reply<T> callback;T completed;CalendarRepository.Failure error;
            synchronized(this){callback=reply;completed=value;error=failure;reply=null;value=null;failure=null;}
            if(callback!=null)callback.finish(completed,error);
        }
        void cancel(){stop(false);}
        private void stop(boolean timeout){
            CalendarRepository.Reply<T> callback;Thread thread;boolean cleanup;
            synchronized(this){
                if(stopped)return;stopped=true;callback=timeout?reply:null;reply=null;value=null;failure=null;work=null;
                thread=runner;cleanup=!workerDone;if(cleanup)cleanupPending=true;
                if(IO.remove(this))workerDone=true;
            }
            MAIN.removeCallbacks(deadline);MAIN.removeCallbacks(publication);signal.cancel();
            if(thread!=null)thread.interrupt();
            if(cleanup)CLOSER.execute(()->{
                try{
                    closeResources(true);
                    try{openSignal.cancel();}catch(RuntimeException unavailable){/* Detached; never leak provider exception text. */}
                }
                finally{synchronized(this){cleanupPending=false;}releaseIfDone();}
            });
            releaseIfDone();
            if(callback!=null)callback.finish(null,new CalendarRepository.Failure("File access timed out. No calendar data changed; discard any incomplete new export file."));
        }
    }
}
