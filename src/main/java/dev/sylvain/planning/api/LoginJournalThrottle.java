package dev.sylvain.planning.api;

import java.time.Duration;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * What keeps the admin login journal bounded when the form is attacked from
 * many addresses at once.
 *
 * <p>The lock is per address, so an attacker rotating addresses is never
 * locked for long, and each of their failures would be one more row and one
 * more task for the worker pool. Two bounds stop that, and both drop rather
 * than wait — the journal is a trace, never a reason to slow the form:</p>
 *
 * <ul>
 * <li><b>a ceiling of failed attempts per window</b>, instance-wide: the
 * first ones of the window are written — enough to read that an attack is
 * under way, and when —, the rest are counted. A successful login is never
 * held by it: it cannot be mass-produced without the password, and it is the
 * line the journal is read for;</li>
 * <li><b>a ceiling of writes pending</b>, for every line: a database that
 * answers slowly does not pile tasks up behind it.</li>
 * </ul>
 *
 * <p>A saturated window says so in <b>one</b> warning line, on its first
 * dropped attempt; the count of what it dropped follows in one line when the
 * next window opens. The lock itself counts every failure either way: only
 * the trace is bounded.</p>
 */
final class LoginJournalThrottle {

    private static final Logger LOG = Logger.getLogger(LoginJournalThrottle.class);

    private final int failuresPerWindow;

    private final Duration window;

    private final int maxPending;

    private Instant windowStart = Instant.EPOCH;

    private int failuresInWindow;

    private long droppedInWindow;

    private int pending;

    private long droppedTotal;

    LoginJournalThrottle(int failuresPerWindow, Duration window, int maxPending) {
        this.failuresPerWindow = failuresPerWindow;
        this.window = window;
        this.maxPending = maxPending;
    }

    /**
     * Whether the lines of one attempt may be written. {@code true} holds a
     * pending slot, which the caller gives back through {@link #done()} once
     * the write is over, whatever its outcome.
     */
    synchronized boolean admit(boolean failure, Instant now) {
        if (!now.isBefore(windowStart.plus(window))) {
            if (droppedInWindow > 0) {
                LOG.infof(
                        "Admin login journal: %d attempt(s) were not journalled between %s and %s",
                        droppedInWindow, windowStart, windowStart.plus(window));
            }
            windowStart = now;
            failuresInWindow = 0;
            droppedInWindow = 0;
        }
        if (pending >= maxPending || (failure && failuresInWindow >= failuresPerWindow)) {
            droppedInWindow++;
            droppedTotal++;
            if (droppedInWindow == 1) {
                LOG.warnf(
                        "Admin login journal saturated (%d failed attempts per %s, %d writes pending at most):"
                                + " attempts are counted but not journalled until %s; the lock still sees every one",
                        failuresPerWindow, window, maxPending, windowStart.plus(window));
            }
            return false;
        }
        pending++;
        if (failure) {
            failuresInWindow++;
        }
        return true;
    }

    /** Gives back the slot an admitted write held. */
    synchronized void done() {
        pending--;
    }

    /** How many attempts were left out of the journal since the start. */
    synchronized long dropped() {
        return droppedTotal;
    }
}
