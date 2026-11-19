package dev.sylvain.planning.solver.handsearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The plateau of the direct engine against times worked out by hand from the
 * source of Timefold's unimproved-time terminations (window 180, gain 1 000 —
 * a quarter of a point a seat on four thousand seats). Nothing here runs
 * those classes: they are internal, built around solver scopes no test can
 * hand them.
 */
class PlateauTest {

    private static final long WINDOW = 180;
    private static final long GAIN = 1_000;

    /**
     * A plan gaining 250 points every 30 s — the gain in 120 s, well inside
     * the window — until 300 s, then nothing. Every best plan from 120 s on is
     * the gain better than the one found 120 s before it, so each pushes the
     * end a window away: the last at 300 s, the end at 480 s. Judged against
     * the score of the last push instead, the solve would end at 420 s.
     */
    @Test
    void aTrickleThatBringsTheGainWithinEveryWindowKeepsTheSolveAliveUntilAWindowAfterItStops() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        long lost = 10_000;
        for (long t = 0; t <= 300; t += 30) {
            plateau.best(t, 0, lost);
            lost -= 250;
        }

        assertThat(plateau.over(420)).isFalse();
        assertThat(plateau.over(480)).isFalse();
        assertThat(plateau.over(481)).isTrue();
    }

    @Test
    void aTrickleThatNeverBringsTheGainWithinAWindowEndsAWindowAfterTheStart() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        long lost = 10_000;
        for (long t = 0; t <= 180; t += 60) {
            plateau.best(t, 0, lost);
            lost -= 250;
            assertThat(plateau.over(t)).isFalse();
        }

        assertThat(plateau.over(181)).isTrue();
    }

    /**
     * The plan the search starts from — 10 000 points lost — is not a
     * reference: the whole gain found 100 s in is only kept for the next one
     * to be measured against, and the solve ends a window after its start.
     * With the starting plan as a reference it would run to 280 s.
     */
    @Test
    void thePlanTheSearchStartsFromIsNotAReference() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        plateau.best(100, 0, 9_000);

        assertThat(plateau.over(180)).isFalse();
        assertThat(plateau.over(181)).isTrue();
    }

    /** Found during the search, the first feasible plan is a best plan like another: the next gain counts against it. */
    @Test
    void aFeasiblePlanFoundByTheSearchPushesTheEndAndServesAsAReference() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        plateau.best(10, 3, 12_000);
        plateau.best(50, 0, 10_000);
        assertThat(plateau.over(190)).isFalse();

        plateau.best(150, 0, 9_000);

        assertThat(plateau.over(330)).isFalse();
        assertThat(plateau.over(331)).isTrue();
    }

    /** No better plan for longer than the window, then a feasible one: the window was already spent. */
    @Test
    void aFeasiblePlanThatComesAfterAStallLongerThanTheWindowEndsTheSolve() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        plateau.best(10, 5, 12_000);
        plateau.best(300, 0, 10_000);

        assertThat(plateau.over(300)).isTrue();
    }

    @Test
    void theGainCountsOnlyAgainstABestPlanNoOlderThanTheWindow() {
        Plateau plateau = new Plateau(0, WINDOW, GAIN);
        plateau.best(0, 0, 10_000);
        // The whole gain, but a second too late to be measured against the plan of the start.
        plateau.best(181, 0, 9_000);

        assertThat(plateau.over(182)).isTrue();
    }

    @Test
    void withoutAGainAnyBetterPlanPushesTheEndAwayAndTheWindowItselfEndsIt() {
        Plateau strict = new Plateau(0, WINDOW, 0);
        assertThat(strict.over(179)).isFalse();
        strict.best(170, 0, 10_000);

        assertThat(strict.over(349)).isFalse();
        assertThat(strict.over(350)).isTrue();
    }

    @Test
    void withoutAWindowThereIsNoPlateau() {
        Plateau none = new Plateau(0, 0, GAIN);
        none.best(0, 0, 10_000);

        assertThat(none.over(Long.MAX_VALUE)).isFalse();
    }
}
