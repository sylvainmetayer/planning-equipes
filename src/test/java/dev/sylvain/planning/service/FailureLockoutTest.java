package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.service.FailureLockout.Outcome;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FailureLockoutTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    /**
     * The race the reservation closes: a burst sent in parallel used to read
     * « no failure yet » a hundred times over. Reserved first, no more than the
     * ceiling can be in flight at once.
     */
    @Test
    void aBurstInFlightGetsNoMoreAttemptsThanTheCeiling() {
        FailureLockout lockout = new FailureLockout(100);

        long granted = IntStream.range(0, 120)
                .filter(i -> lockout.reserve("1.2.3.4", 5, TEN_MINUTES) == 0)
                .count();

        assertThat(granted).isEqualTo(5);
        assertThat(lockout.reserve("1.2.3.4", 5, TEN_MINUTES)).isEqualTo(1);
    }

    @Test
    void failuresLockTheKeyAndASuccessClearsTheRun() {
        FailureLockout lockout = new FailureLockout(100);

        for (int i = 0; i < 2; i++) {
            assertThat(lockout.reserve("k", 3, TEN_MINUTES)).isZero();
            lockout.settle("k", Outcome.FAILURE, TEN_MINUTES);
        }
        assertThat(lockout.reserve("k", 3, TEN_MINUTES)).isZero();
        lockout.settle("k", Outcome.SUCCESS, TEN_MINUTES);
        assertThat(lockout.lockoutSeconds("k", 3, TEN_MINUTES)).isZero();

        for (int i = 0; i < 3; i++) {
            assertThat(lockout.reserve("k", 3, TEN_MINUTES)).isZero();
            lockout.settle("k", Outcome.FAILURE, TEN_MINUTES);
        }
        assertThat(lockout.lockoutSeconds("k", 3, TEN_MINUTES)).isBetween(1L, TEN_MINUTES.toSeconds());
        assertThat(lockout.reserve("k", 3, TEN_MINUTES)).isGreaterThan(1);
    }

    @Test
    void anAttemptThatTriedNothingGivesItsSlotBackWithoutCounting() {
        FailureLockout lockout = new FailureLockout(100);

        for (int i = 0; i < 10; i++) {
            assertThat(lockout.reserve("k", 2, TEN_MINUTES)).isZero();
            lockout.settle("k", Outcome.NEITHER, TEN_MINUTES);
        }

        assertThat(lockout.lockoutSeconds("k", 2, TEN_MINUTES)).isZero();
    }

    @Test
    void theLockLiftsAFullDurationAfterTheLastFailure() throws InterruptedException {
        FailureLockout lockout = new FailureLockout(100);
        Duration shortDuration = Duration.ofMillis(20);
        lockout.recordFailure("k", shortDuration);
        lockout.recordFailure("k", shortDuration);
        assertThat(lockout.lockoutSeconds("k", 2, shortDuration)).isEqualTo(1);

        Thread.sleep(50);

        assertThat(lockout.lockoutSeconds("k", 2, shortDuration)).isZero();
        assertThat(lockout.reserve("k", 2, shortDuration)).isZero();
    }

    /** Past the ceiling the oldest run goes — but never one with an attempt still in flight. */
    @Test
    void aFullMapEvictsTheOldestRunButKeepsTheOnesInFlight() {
        FailureLockout lockout = new FailureLockout(2);
        assertThat(lockout.reserve("in-flight", 1, TEN_MINUTES)).isZero();
        lockout.recordFailure("old", TEN_MINUTES);

        lockout.recordFailure("new", TEN_MINUTES);

        assertThat(lockout.lockoutSeconds("old", 1, TEN_MINUTES)).as("evicted").isZero();
        assertThat(lockout.lockoutSeconds("new", 1, TEN_MINUTES)).isPositive();
        assertThat(lockout.reserve("in-flight", 1, TEN_MINUTES))
                .as("its slot is still held")
                .isEqualTo(1);
    }
}
