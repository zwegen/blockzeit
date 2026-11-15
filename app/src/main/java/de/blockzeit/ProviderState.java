package de.blockzeit;

/** Process-wide short cache/reservation transactions; never held across HTTP or job dispatch. */
final class ProviderState {
    static final Object LOCK = new Object();
    private ProviderState() { }
    static <E extends Exception, F extends Exception> void guard(RefreshCycle.Action<E, F> action) throws E, F {
        synchronized (LOCK) { action.run(); }
    }
}
