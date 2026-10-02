package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LoginJournalThrottleTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private static final Duration HOUR = Duration.ofHours(1);

    /**
     * A distributed attack is never locked for long, so each of its failures
     * would be a row: past the ceiling of the window they are counted, not
     * written — and the window after starts afresh.
     */
    @Test
    void failuresPastTheCeilingOfTheWindowAreDroppedAndCounted() {
        LoginJournalThrottle throttle = new LoginJournalThrottle(3, HOUR, 100);

        long admitted = IntStream.range(0, 10)
                .filter(i -> admitAndFinish(throttle, true, NOW.plusSeconds(i)))
                .count();

        assertThat(admitted).isEqualTo(3);
        assertThat(throttle.dropped()).isEqualTo(7);
        assertThat(admitAndFinish(throttle, true, NOW.plus(HOUR))).isTrue();
    }

    /** A successful login cannot be mass-produced, and it is the line the journal is read for. */
    @Test
    void aSuccessfulLoginIsNotHeldByTheCeilingOfFailures() {
        LoginJournalThrottle throttle = new LoginJournalThrottle(1, HOUR, 100);
        assertThat(admitAndFinish(throttle, true, NOW)).isTrue();
        assertThat(admitAndFinish(throttle, true, NOW)).isFalse();

        assertThat(admitAndFinish(throttle, false, NOW)).isTrue();
    }

    /** A slow database does not pile tasks up behind it: past the pending ceiling, every line is dropped. */
    @Test
    void writesPendingAreBoundedForEveryLine() {
        LoginJournalThrottle throttle = new LoginJournalThrottle(100, HOUR, 2);
        assertThat(throttle.admit(true, NOW)).isTrue();
        assertThat(throttle.admit(false, NOW)).isTrue();

        assertThat(throttle.admit(false, NOW)).isFalse();
        assertThat(throttle.admit(true, NOW)).isFalse();

        throttle.done();
        assertThat(throttle.admit(false, NOW)).isTrue();
        assertThat(throttle.dropped()).isEqualTo(2);
    }

    private static boolean admitAndFinish(LoginJournalThrottle throttle, boolean failure, Instant now) {
        boolean admitted = throttle.admit(failure, now);
        if (admitted) {
            throttle.done();
        }
        return admitted;
    }
}
