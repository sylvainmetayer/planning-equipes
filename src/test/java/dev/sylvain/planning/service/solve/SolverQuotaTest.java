package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The operator's guard on solving: the sliding hourly quota and the queue cap, without a container. */
class SolverQuotaTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /** 12:00 UTC on a summer day: 14:00 in Paris. */
    private static final Instant NOON_UTC = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void theQuotaIsOffByDefault() {
        SolverQuota quota = new SolverQuota(0, 0, PARIS, new MovableClock(NOON_UTC));

        assertThatCode(() -> {
                    for (int i = 0; i < 1000; i++) {
                        quota.consume();
                    }
                    quota.checkQueue(1000);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void aNegativeSettingMeansNoLimitEither() {
        SolverQuota quota = new SolverQuota(-1, -1, PARIS, new MovableClock(NOON_UTC));

        assertThatCode(() -> {
                    quota.consume();
                    quota.checkQueue(50);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void pastTheQuotaARunIsRefusedNamingTheMinuteInTheInstanceZone() {
        MovableClock clock = new MovableClock(NOON_UTC);
        SolverQuota quota = new SolverQuota(3, 0, PARIS, clock);

        quota.consume();
        clock.advance(Duration.ofMinutes(10));
        quota.consume();
        clock.advance(Duration.ofMinutes(10));
        quota.consume();

        // The oldest run, at 14:00 in Paris, leaves the window at 15:00.
        assertThatThrownBy(quota::consume)
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessage("Cette instance limite le calcul à 3 résolutions par heure. "
                        + "Prochaine résolution possible à 15:00.");
    }

    @Test
    void theWindowSlidesRunByRunRatherThanResettingOnTheHour() {
        MovableClock clock = new MovableClock(NOON_UTC);
        SolverQuota quota = new SolverQuota(2, 0, PARIS, clock);
        quota.consume();
        clock.advance(Duration.ofMinutes(40));
        quota.consume();

        // 60 min after the first run: it has left the window, the second has not.
        clock.advance(Duration.ofMinutes(20));
        assertThatCode(quota::consume).doesNotThrowAnyException();
        // Full again — and the next slot is the second run's, at 14:40 + 1 h.
        assertThatThrownBy(quota::consume).hasMessageContaining("à 15:40.");
    }

    @Test
    void aRefusedLaunchConsumesNothing() {
        MovableClock clock = new MovableClock(NOON_UTC);
        SolverQuota quota = new SolverQuota(1, 0, PARIS, clock);
        quota.consume();
        clock.advance(Duration.ofMinutes(30));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(quota::consume).isInstanceOf(BusinessError.Conflict.class);
        }

        // Refusals did not push the window back: the first run's hour is all that counts.
        clock.advance(Duration.ofMinutes(30));
        assertThatCode(quota::consume).doesNotThrowAnyException();
    }

    @Test
    void theNextMinuteIsRoundedUpSoTheReaderIsNotSentBackTooEarly() {
        MovableClock clock = new MovableClock(NOON_UTC.plusSeconds(20));
        SolverQuota quota = new SolverQuota(1, 0, PARIS, clock);
        quota.consume();

        // Free at 15:00:20: « 15:00 » would earn a second refusal.
        assertThatThrownBy(quota::consume).hasMessageContaining("à 15:01.");
    }

    @Test
    void aQuotaOfOneSaysItInTheSingular() {
        SolverQuota quota = new SolverQuota(1, 1, ZoneOffset.UTC, new MovableClock(NOON_UTC));
        quota.consume();

        assertThatThrownBy(quota::consume)
                .hasMessage("Cette instance limite le calcul à 1 résolution par heure. "
                        + "Prochaine résolution possible à 13:00.");
        assertThatThrownBy(() -> quota.checkQueue(1))
                .hasMessage("Cette instance limite la file d'attente du solveur à 1 résolution planifiée. "
                        + "Attendez qu'une résolution démarre, ou retirez-en une de la file.");
    }

    @Test
    void theQueueCapCountsTheJobsWaitingNotTheRunningOne() {
        SolverQuota quota = new SolverQuota(0, 2, PARIS, new MovableClock(NOON_UTC));

        assertThatCode(() -> quota.checkQueue(0)).doesNotThrowAnyException();
        assertThatCode(() -> quota.checkQueue(1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> quota.checkQueue(2))
                .isInstanceOf(BusinessError.Conflict.class)
                .hasMessageStartingWith(
                        "Cette instance limite la file d'attente du solveur à 2 résolutions planifiées.");
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
