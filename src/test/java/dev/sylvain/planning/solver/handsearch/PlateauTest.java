package dev.sylvain.planning.solver.handsearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The plateau of the direct engine is the other search's, case by case: the
 * times below are the ones Timefold's own termination gives on the same
 * sequence of best scores (window 180, gain 1 000 — a quarter of a point a
 * seat on four thousand seats).
 */
class PlateauTest {

    private static final long WINDOW = 180;
    private static final long GAIN = 1_000;

    /**
     * A plan gaining 250 medium points every 30 s — the gain in 120 s, well
     * inside the window — until 300 s, then nothing. Every best plan from
     * 120 s on is the gain better than the one found 120 s before it, so each
     * pushes the end a window away: the last at 300 s, the end at 480 s.
     * Judged against the score of the last push instead, the solve would end
     * at 420 s — a window after 240 s, the last time a whole gain had piled up.
     */
    @Test
    void aTrickleThatBringsTheGainWithinEveryWindowKeepsTheSolveAliveUntilAWindowAfterItStops() {
        Plateau plateau = new Plateau(WINDOW, GAIN);
        long lost = 10_000;
        for (long t = 0; t <= 300; t += 30) {
            plateau.best(t, lost);
            lost -= 250;
        }

        assertThat(plateau.over(420)).isFalse();
        assertThat(plateau.over(480)).isFalse();
        assertThat(plateau.over(481)).isTrue();
    }

    @Test
    void aTrickleThatNeverBringsTheGainWithinAWindowEndsAWindowAfterFeasibility() {
        Plateau plateau = new Plateau(WINDOW, GAIN);
        long lost = 10_000;
        for (long t = 0; t <= 180; t += 60) {
            plateau.best(t, lost);
            lost -= 250;
            assertThat(plateau.over(t)).isFalse();
        }

        assertThat(plateau.over(181)).isTrue();
    }

    @Test
    void theGainCountsOnlyAgainstABestPlanNoOlderThanTheWindow() {
        Plateau plateau = new Plateau(WINDOW, GAIN);
        plateau.best(0, 10_000);
        // The whole gain, but a second too late to be measured against the plan of the start.
        plateau.best(181, 9_000);

        assertThat(plateau.over(182)).isTrue();
    }

    @Test
    void withoutAGainAnyBetterPlanPushesTheEndAway() {
        Plateau strict = new Plateau(WINDOW, 0);
        strict.best(0, 10_000);
        strict.best(170, 10_000);

        assertThat(strict.over(340)).isFalse();
        assertThat(strict.over(351)).isTrue();
    }

    @Test
    void withoutAWindowThereIsNoPlateau() {
        Plateau none = new Plateau(0, GAIN);
        none.best(0, 10_000);

        assertThat(none.over(Long.MAX_VALUE)).isFalse();
    }
}
