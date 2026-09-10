package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The comparison of issue #274. Pure logic, no database: what it has to get
 * right is the ordering of a Timefold score, and the cases where it must
 * refuse to have an opinion.
 */
class PreviousPlanTest {

    /**
     * The case that motivated the issue, measured on the real 2026 profile: a
     * re-solve from scratch reached feasibility just like the plan it
     * replaced, and lost 1 202 medium points doing so. Reading the hard score
     * alone — which is what the screen showed — that run looked like a success.
     */
    @Test
    void detectsALossOnMediumAtEqualHardScore() {
        assertThat(PreviousPlan.isDegraded("0hard/-6232medium/-920soft", "0hard/-7434medium/-564soft"))
                .isTrue();
    }

    @Test
    void reportsNoLossWhenTheSolveImproved() {
        assertThat(PreviousPlan.isDegraded("0hard/-6232medium/-920soft", "0hard/-6047medium/-852soft"))
                .isFalse();
    }

    /** Soft only breaks the tie once hard and medium are equal, as Timefold orders them. */
    @Test
    void ranksSoftLastAndHardFirst() {
        assertThat(PreviousPlan.isDegraded("0hard/-100medium/-10soft", "0hard/-100medium/-11soft"))
                .isTrue();
        assertThat(PreviousPlan.isDegraded("-1hard/-100medium/-10soft", "0hard/-9999medium/-9999soft"))
                .isFalse();
    }

    @Test
    void reportsNoLossWhenTheScoreIsUnchanged() {
        assertThat(PreviousPlan.isDegraded("0hard/-6232medium/-920soft", "0hard/-6232medium/-920soft"))
                .isFalse();
    }

    /**
     * No score, no verdict: raising a false alarm about a solve that may well
     * have improved things would be worse than saying nothing.
     */
    @Test
    void staysSilentWhenAScoreIsMissingOrUnreadable() {
        assertThat(PreviousPlan.isDegraded(null, "0hard/-1medium/0soft")).isFalse();
        assertThat(PreviousPlan.isDegraded("0hard/-1medium/0soft", null)).isFalse();
        assertThat(PreviousPlan.isDegraded("", "0hard/-1medium/0soft")).isFalse();
        assertThat(PreviousPlan.isDegraded("pas un score", "0hard/-1medium/0soft"))
                .isFalse();
    }

    /** First solve of an edition: nothing was captured, so there is nothing to compare. */
    @Test
    void offersNoComparisonWithoutASnapshot() {
        assertThat(PreviousPlan.of(null, "0hard/0medium/0soft", "0hard/0medium/0soft"))
                .isNull();
    }

    /**
     * A snapshot whose score could not be established still names the plan to
     * restore — the recap simply does not draw the comparison.
     */
    @Test
    void keepsTheSnapshotToRestoreEvenWithoutAScore() {
        PreviousPlan previous = PreviousPlan.of(42L, null, "0hard/-1medium/0soft");

        assertThat(previous).isNotNull();
        assertThat(previous.snapshotId()).isEqualTo(42L);
        assertThat(previous.score()).isNull();
        assertThat(previous.degraded()).isFalse();
    }
}
