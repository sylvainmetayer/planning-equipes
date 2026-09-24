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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When a solve runs in two stages (ADR 0050), and what it reports: only with
 * the hard run-of-days rule on <b>and</b> a published plan <b>and</b> the
 * stability rule active — every other edition keeps the single stage it had.
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
    void appliesOnlyWithTheHardRunRuleAPublishedPlanAndTheStabilityRule() {
        List<ConstraintToggle> hardRun = List.of(new ConstraintToggle(HARD_RUN_RULE, true));
        List<AffectationPubliee> publication = List.of(published(x));

        assertThat(FeasibilityFirstSolve.applies(prepared(hardRun, publication)))
                .isTrue();
        assertThat(FeasibilityFirstSolve.applies(prepared(List.of(), publication)))
                .as("the hard rule off, as it ships")
                .isFalse();
        assertThat(FeasibilityFirstSolve.applies(prepared(hardRun, List.of())))
                .as("nothing published: no price to suspend")
                .isFalse();
        List<ConstraintToggle> stabilityOff = new ArrayList<>(hardRun);
        stabilityOff.add(new ConstraintToggle(FeasibilityFirstSolve.STABILITY_RULE, false));
        assertThat(FeasibilityFirstSolve.applies(prepared(stabilityOff, publication)))
                .as("the stability rule switched off by the edition")
                .isFalse();
        assertThat(FeasibilityFirstSolve.applies(null)).isFalse();
    }

    @Test
    void theFirstStageTakesAtMostTwoThirdsOfTheBudget() {
        assertThat(FeasibilityFirstSolve.feasibilitySeconds(300)).isEqualTo(200);
        assertThat(FeasibilityFirstSolve.feasibilitySeconds(900)).isEqualTo(600);
        assertThat(FeasibilityFirstSolve.feasibilitySeconds(1)).isEqualTo(1);
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
