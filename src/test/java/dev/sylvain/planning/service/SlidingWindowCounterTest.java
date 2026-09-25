package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The distinct count behind {@code ColleagueLookupLimiter}, and the ceiling of a counter keyed by source address. */
class SlidingWindowCounterTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void aColleagueAlreadySeenIsFreeAndANewOnePastTheCeilingIsRefused() {
        SlidingWindowCounter counter = new SlidingWindowCounter();

        assertThat(counter.useDistinct("A1", "B", 2, HOUR).autorise()).isTrue();
        assertThat(counter.useDistinct("A1", "C", 2, HOUR).autorise()).isTrue();
        assertThat(counter.useDistinct("A1", "B", 2, HOUR).autorise()).isTrue();

        RateLimitVerdict refusal = counter.useDistinct("A1", "D", 2, HOUR);
        assertThat(refusal.autorise()).isFalse();
        assertThat(refusal.secondsBeforeNextTry()).isBetween(1L, HOUR.toSeconds());

        // Refused and not remembered: C is still free, D is still refused.
        assertThat(counter.useDistinct("A1", "C", 2, HOUR).autorise()).isTrue();
        assertThat(counter.useDistinct("A1", "D", 2, HOUR).autorise()).isFalse();
    }

    @Test
    void eachAnimateurHasACountOfItsOwn() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        counter.useDistinct("A1", "B", 1, HOUR);

        assertThat(counter.useDistinct("A1", "C", 1, HOUR).autorise()).isFalse();
        assertThat(counter.useDistinct("A2", "C", 1, HOUR).autorise()).isTrue();
    }

    @Test
    void theWindowReopensWithAnEmptyCount() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        Duration court = Duration.ofMillis(20);
        counter.useDistinct("A1", "B", 1, court);
        assertThat(counter.useDistinct("A1", "C", 1, court).autorise()).isFalse();

        // The window reads the wall clock: only time passing reopens it.
        await().pollDelay(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(
                                counter.useDistinct("A1", "C", 1, court).autorise())
                        .isTrue());
    }

    /**
     * A counter keyed by source address is bounded: a new address past the
     * ceiling evicts the oldest window, and one already counted evicts nothing.
     */
    @Test
    void aBoundedCounterEvictsTheOldestOnlyForANewKey() {
        SlidingWindowCounter counter = SlidingWindowCounter.bounded(2);
        counter.use("a", 1, HOUR);
        counter.use("b", 1, HOUR);

        assertThat(counter.use("b", 1, HOUR).autorise())
                .as("b, known, stays counted")
                .isFalse();
        assertThat(counter.use("c", 1, HOUR).autorise()).isTrue();

        assertThat(counter.use("a", 1, HOUR).autorise())
                .as("a was the oldest: evicted")
                .isTrue();
    }
}
