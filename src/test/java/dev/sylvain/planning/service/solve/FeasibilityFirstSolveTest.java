package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When a solve runs in two stages (ADR 0067), and what it reports: only with
 * a published plan <b>and</b> the stability rule active — every other edition
 * keeps the single stage it had, whichever rules it holds hard.
 */
class FeasibilityFirstSolveTest {

    private static final String HARD_RUN_RULE = "maxJoursConsecutifsTravaillesDur";
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private final Stand stand = new Stand("S", "Stand", Set.of(), 1, 2, false);
    private final Creneau morning = new Creneau(1L, 1, MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Animateur x = new Animateur("X", "Xavier", "Un", LocalDate.of(1990, 1, 1), false);
    private final Animateur y = new Animateur("Y", "Yann", "Deux", LocalDate.of(1990, 1, 1), false);

    private PlanningEvenement prepared(List<ConstraintToggle> toggles, List<AffectationPubliee> published) {
        PosteAffectation first = new PosteAffectation("P1", stand, morning);
        PosteAffectation second = new PosteAffectation("P2", stand, morning);
        first.setAnimateur(x);
        second.setAnimateur(y);
        PlanningEvenement problem = new PlanningEvenement(MONDAY, List.of(x, y), List.of(first, second));
        problem.setParametresQualite(List.of(new ParametresQualite()));
        problem.setConstraintsDesactivees(toggles);
        problem.setAffectationsPubliees(published);
        return problem;
    }

    private AffectationPubliee published(Animateur animateur) {
        return new AffectationPubliee(
                stand.getId(), morning.getDate(), morning.getHeureDebut(), morning.getHeureFin(), animateur.getId());
    }

    @Test
    void appliesOnlyWithAPublishedPlanAndTheStabilityRule() {
        List<AffectationPubliee> publication = List.of(published(x));

        assertThat(FeasibilityFirstSolve.applies(prepared(List.of(), publication)))
                .as("a published plan, the stability rule as it ships")
                .isTrue();
        assertThat(FeasibilityFirstSolve.applies(
                        prepared(List.of(new ConstraintToggle(HARD_RUN_RULE, true)), publication)))
                .as("the hard run of days changes nothing to it")
                .isTrue();
        assertThat(FeasibilityFirstSolve.applies(prepared(List.of(), List.of())))
                .as("nothing published: no price to suspend")
                .isFalse();
        assertThat(FeasibilityFirstSolve.applies(prepared(
                        List.of(new ConstraintToggle(FeasibilityFirstSolve.STABILITY_RULE, false)), publication)))
                .as("the stability rule switched off by the edition")
                .isFalse();
        assertThat(FeasibilityFirstSolve.applies(null)).isFalse();
    }

    @Test
    void theFirstStageTakesAtMostTwoThirdsOfTheBudget() {
        assertThat(FeasibilityFirstSolve.feasibilityMillis(300_000)).isEqualTo(200_000);
        assertThat(FeasibilityFirstSolve.feasibilityMillis(900_000)).isEqualTo(600_000);
        assertThat(FeasibilityFirstSolve.feasibilityMillis(1)).isEqualTo(1);
    }

    /** A stage that ran at all is never announced as « 0 s ». */
    @Test
    void aStageDurationIsRoundedUpToTheSecond() {
        assertThat(FeasibilityFirstSolve.roundUpToSeconds(0)).isZero();
        assertThat(FeasibilityFirstSolve.roundUpToSeconds(80)).isEqualTo(1);
        assertThat(FeasibilityFirstSolve.roundUpToSeconds(1000)).isEqualTo(1);
        assertThat(FeasibilityFirstSolve.roundUpToSeconds(119_400)).isEqualTo(120);
    }

    /** One per seat of a published line whose holder was not named there — an empty seat included. */
    @Test
    void countsThePublishedSeatsAPlanChanged() {
        PlanningEvenement plan = prepared(List.of(), List.of(published(x), published(y)));
        assertThat(FeasibilityFirstSolve.publishedSeatsChanged(plan)).isZero();

        // The two holders swap seats: same line, same people — nothing to tell anyone.
        plan.getPostes().get(0).setAnimateur(y);
        plan.getPostes().get(1).setAnimateur(x);
        assertThat(FeasibilityFirstSolve.publishedSeatsChanged(plan)).isZero();

        plan.getPostes().get(1).setAnimateur(null);
        assertThat(FeasibilityFirstSolve.publishedSeatsChanged(plan)).isEqualTo(1);

        // A past seat is what was worked, and the rule does not charge it.
        plan.getPostes().get(1).setPasse(true);
        assertThat(FeasibilityFirstSolve.publishedSeatsChanged(plan)).isZero();
    }
}
