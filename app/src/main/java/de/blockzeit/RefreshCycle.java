package de.blockzeit;

import java.util.List;
import java.util.concurrent.Executor;

/** Up to four bounded independent lanes; only short cache commits share this cycle's monitor. */
final class RefreshCycle {
    interface Action<E extends Exception, F extends Exception> { void run() throws E, F; }
    interface Lane { void run(); void cancel(); }
    interface Listener { void publish(); void finished(RefreshCycle owner, boolean cancelled); }
    private final Executor worker, main;
    private final Listener listener;
    private List<Lane> lanes;
    private final Thread[] threads = new Thread[4];
    private boolean cancelled, finished;
    private int remaining;

    RefreshCycle(Executor worker, Executor main, Listener listener) {
        this.worker = worker; this.main = main; this.listener = listener;
    }
    synchronized boolean active() { return !cancelled && !finished; }
    /** Cancellation and commits are atomic with one another, never with network I/O. */
    synchronized <E extends Exception, F extends Exception> boolean guard(Action<E, F> action) throws E, F {
        if (!active()) return false;
        ProviderState.guard(action);
        return true;
    }
    synchronized <E extends Exception, F extends Exception> boolean commit(Action<E, F> action,
            java.util.function.BooleanSupplier publicationSource) throws E, F {
        if (!active()) return false;
        ProviderState.guard(action);
        main.execute(() -> {
            synchronized (RefreshCycle.this) {
                if (active() && publicationSource.getAsBoolean()) listener.publish();
            }
        });
        return true;
    }
    synchronized <E extends Exception, F extends Exception> boolean commit(Action<E, F> action) throws E, F {
        return commit(action, () -> true);
    }
    void start(List<Lane> work) {
        synchronized (this) {
            if (lanes != null || work.isEmpty() || work.size() > threads.length) throw new IllegalStateException("One to four lanes, once");
            lanes = List.copyOf(work);
            remaining = lanes.size();
        }
        for (int i = 0; i < work.size(); i++) {
            final int index = i;
            try { worker.execute(() -> runLane(index)); }
            catch (java.util.concurrent.RejectedExecutionException rejected) { complete(); }
        }
    }
    private void runLane(int index) {
        synchronized (this) { threads[index] = Thread.currentThread(); }
        try { if (active()) lanes.get(index).run(); }
        finally {
            synchronized (this) { threads[index] = null; }
            // A cancelled lane must not leak its interrupt into the executor's next task.
            Thread.interrupted();
            complete();
        }
    }
    private synchronized void complete() {
        if (--remaining != 0) return;
        main.execute(() -> {
            synchronized (RefreshCycle.this) {
                if (finished) return;
                finished = true;
                listener.finished(RefreshCycle.this, cancelled);
            }
        });
    }
    void cancel() {
        List<Lane> stopping;
        synchronized (this) {
            if (cancelled || finished) return;
            cancelled = true;
            stopping = lanes;
        }
        if (stopping != null) for (Lane lane : stopping) lane.cancel();
        synchronized (this) {
            for (Thread thread : threads) if (thread != null) thread.interrupt();
        }
        // Keep admission until all wrappers have drained, including work queued before cancellation.
    }
}
