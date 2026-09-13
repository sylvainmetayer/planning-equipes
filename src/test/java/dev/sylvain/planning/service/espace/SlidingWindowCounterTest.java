package dev.sylvain.planning.service.espace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The distinct count behind {@link ColleagueLookupLimiter}; the plain count is covered through its two limiters. */
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
    void theWindowReopensWithAnEmptyCount() throws InterruptedException {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        Duration court = Duration.ofMillis(20);
        counter.useDistinct("A1", "B", 1, court);
        assertThat(counter.useDistinct("A1", "C", 1, court).autorise()).isFalse();

        Thread.sleep(50);

        assertThat(counter.useDistinct("A1", "C", 1, court).autorise()).isTrue();
    }
}
