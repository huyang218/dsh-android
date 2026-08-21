package io.github.huyang218.dshandroid;

/**
 * What the host is doing right now, published by the service and read by
 * whatever screen is up.
 *
 * <p>Static volatile fields rather than a bound Service, a Binder or a
 * broadcast: there is exactly one producer ({@link NodeService}'s startup
 * thread), one consumer ({@link MainActivity}'s poll loop, which already runs
 * every 250 ms waiting for the port), and both live in the same process. A
 * Binder round trip would buy nothing and cost a lifecycle to get wrong.
 *
 * <p>This exists because the first launch after an install spends a minute or
 * more unpacking, and a screen that says nothing for a minute is
 * indistinguishable from a screen that has hung.
 */
final class HostStatus {

    /** Coarse state of the startup sequence. */
    enum Phase {
        /** Nothing has started it yet. */
        IDLE,
        /** Writing the runtime out of the apk. */
        UNPACKING,
        /** Node is starting; the port is not open yet. */
        LAUNCHING,
        /** The port answered. */
        RUNNING,
        /** Startup gave up; {@link #detail()} says why. */
        FAILED,
    }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String detail = "";
    /** 0..100 while unpacking, or -1 when there is no measurable progress. */
    private static volatile int percent = -1;

    private HostStatus() {}

    static Phase phase() {
        return phase;
    }

    static String detail() {
        return detail;
    }

    static int percent() {
        return percent;
    }

    static void unpacking(String what, int pct) {
        detail = what;
        percent = pct;
        phase = Phase.UNPACKING;
    }

    static void launching() {
        detail = "";
        percent = -1;
        phase = Phase.LAUNCHING;
    }

    static void running() {
        phase = Phase.RUNNING;
    }

    static void failed(String why) {
        detail = why == null ? "" : why;
        percent = -1;
        phase = Phase.FAILED;
    }

    /** Forget a previous run's outcome so a retry starts from a clean slate. */
    static void reset() {
        phase = Phase.IDLE;
        detail = "";
        percent = -1;
    }
}
