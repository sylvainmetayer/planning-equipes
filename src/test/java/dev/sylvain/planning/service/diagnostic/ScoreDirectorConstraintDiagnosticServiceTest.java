package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.ConstraintJustification;
import ai.timefold.solver.core.api.score.stream.DefaultConstraintJustification;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;

/**
 * The one behaviour of the score director implementation that the contract test
 * cannot reach today.
 *
 * <p>{@code SolutionManager.analyze()} folds matches sharing a justification
 * into one, so its match count is a count of <em>distinct justifications</em>.
 * No constraint in this project produces a collision — every justification is
 * the tuple its constraint matched on — so running the two implementations side
 * by side proves nothing about this rule. It is asserted directly here instead,
 * to stop it from being quietly dropped as dead code.
 */
class ScoreDirectorConstraintDiagnosticServiceTest {

    private static final Animateur ALICE = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
    private static final Animateur BOB = new Animateur("A2", "Bob", "Durand", LocalDate.of(1990, 1, 1), false);

    @Test
    void matchesSharingAJustificationCountOnce() {
        ConstraintJustification once = justification(ALICE);
        ConstraintJustification again = justification(ALICE);

        List<MatchFacts> matches = ScoreDirectorConstraintDiagnosticService
                .distinctFacts(List.of(once, again));

        assertThat(matches).singleElement()
                .satisfies(match -> assertThat(match.facts()).containsExactly(ALICE));
    }

    @Test
    void matchesOverDifferentFactsAreKeptApart() {
        List<MatchFacts> matches = ScoreDirectorConstraintDiagnosticService
                .distinctFacts(List.of(justification(ALICE), justification(BOB)));

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).facts()).containsExactly(ALICE);
        assertThat(matches.get(1).facts()).containsExactly(BOB);
    }

    /**
     * A justification's identity is its facts alone — the impact it carried is
     * not part of it. Two matches over the same facts therefore fold into one
     * whatever they weighed, which is exactly what {@code analyze()} does (it
     * sums the impacts it folds; nothing to sum here, since a match is only
     * ever read for its facts).
     */
    @Test
    void sameFactsFoldIntoOneWhateverTheyWeighed() {
        ConstraintJustification light = DefaultConstraintJustification
                .of(HardMediumSoftScore.ofHard(-1), List.of(ALICE));
        ConstraintJustification heavy = DefaultConstraintJustification
                .of(HardMediumSoftScore.ofHard(-5), List.of(ALICE));

        assertThat(ScoreDirectorConstraintDiagnosticService.distinctFacts(List.of(light, heavy)))
                .singleElement()
                .satisfies(match -> assertThat(match.facts()).containsExactly(ALICE));
    }

    /** Encounter order is preserved: the violation popup shows the first N. */
    @Test
    void theOrderMatchesWereFoundInIsKept() {
        List<MatchFacts> matches = ScoreDirectorConstraintDiagnosticService
                .distinctFacts(List.of(justification(BOB), justification(ALICE), justification(BOB)));

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).facts()).containsExactly(BOB);
        assertThat(matches.get(1).facts()).containsExactly(ALICE);
    }

    private static ConstraintJustification justification(Animateur animateur) {
        return DefaultConstraintJustification.of(HardMediumSoftScore.ofHard(-1), List.of(animateur));
    }
}
