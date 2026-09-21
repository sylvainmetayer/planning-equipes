package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.brokenHardConstraints;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.matchCounts;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveFor;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConstraintToggle;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.scenario.ScenarioValidator;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.TypeCauseInfaisabilite;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rungs of the ladder that must <b>never</b> solve, each for one named
 * reason. Like {@code PlanningServiceUnsolvableScenarioTest}, they pin the
 * intent: the file is accepted, and the only rules a solve breaks are the ones
 * its reason implies. Where the pre-solve analysis can see the reason, the test
 * says so; where it cannot — a weekly wall, a meal break — the test says that
 * too, because an optimistic estimate is part of what the operator reads. And
 * where two rules could give way, the test pins which one does: an empty seat
 * or an unkept exception, never a seat that breaks an eligibility exclusion.
 */
class ScenarioLadderInfeasibleTest {

    @Test
    void rung26MoreSeatsThanPeople() {
        Loaded loaded = load("gamme-26-infaisable-sous-effectif");
        assertThat(ScenarioValidator.validate(ScenarioLadder.yaml(loaded.name())))
                .isEmpty();
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().manqueAnimateurs()).isEqualTo(3);

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(brokenHardConstraints(solved)).containsExactly("posteDoitEtrePourvu");
        assertThat(matchCounts(solved)).containsEntry("posteDoitEtrePourvu", 3);
    }

    /**
     * The forced assignment falls on the day its animateur declared off. The
     * pre-solve analysis names it as a blocking cause, and the solve keeps the
     * day off: a seat on a day off costs more than any other breach, so the
     * exception is the rule left unkept — never the other way round.
     */
    @Test
    void rung27AForcedAssignmentOnADayOff() {
        Loaded loaded = load("gamme-27-infaisable-affectation-forcee-jour-indisponible");
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().causes()).singleElement().satisfies(cause -> {
            assertThat(cause.type()).isEqualTo(TypeCauseInfaisabilite.AFFECTATION_FORCEE_JOUR_INDISPONIBLE);
            assertThat(cause.contrainteIds()).containsExactly("C01");
        });

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(brokenHardConstraints(solved)).containsExactly("affectationForcee");
        assertThat(ScenarioLadder.seatsOf(solved, "A111")).isEmpty();
    }

    @Test
    void rung28TheSixDayWallTheAnalysisCannotSee() {
        Loaded loaded = load("gamme-28-infaisable-mur-des-six-jours");
        assertThat(loaded.problem().getPostes()).hasSize(21);
        assertThat(loaded.feasibility().feasible()).isTrue();

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(solved.getScore().hardScore()).isNegative();
        assertThat(brokenHardConstraints(solved))
                .isNotEmpty()
                .isSubsetOf("posteDoitEtrePourvu", "maxJoursTravaillesParSemaine", "reposHebdomadaireMinimal");
    }

    /**
     * Only one adult for an evening of two seats: the second seat stays empty.
     * It used to go to the fifteen-year-old — a seat at night cost one hard
     * point, exactly what the empty seat costs.
     */
    @Test
    void rung29AnEveningOnlyMinorsCouldHold() {
        Loaded loaded = load("gamme-29-infaisable-mineurs-en-soiree");

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(brokenHardConstraints(solved)).containsExactly("posteDoitEtrePourvu");
        assertThat(matchCounts(solved)).containsEntry("posteDoitEtrePourvu", 1);
        ScenarioLadder.assertCoreRules(solved);
    }

    /**
     * The geometry is the only cause: the same file, with the two rules that
     * geometry breaks switched off, solves to zero hard. With them on, the
     * solver's cheapest answer is to leave the seat empty rather than to hold
     * it through the meal, so either of the first two rules may be the one left
     * broken.
     *
     * <p>{@code pauseSurPosteSansRelais} is the second rule, and it is there
     * for the same reason as the first: a single-seat stand held from 10:00 to
     * 20:00 owes a break at the sixth hour with nobody on the stand to take
     * over. It became a hard rule with the fortnight framework (issue #31);
     * before that it cost medium points and this half of the test never saw
     * it. Filling the seat is what makes both fire, which is why switching off
     * the meal rule alone no longer reaches zero.</p>
     *
     * <p>Both are therefore allowed in the first assertion, and neither is
     * required: leaving the seat empty costs one hard point and owes no break,
     * holding it costs the meal rule and the break rule, and which of the two
     * the search settles on is not this rung's subject. Naming only the meal
     * rule there passed by arithmetic rather than by design — the empty seat
     * happened to be strictly cheaper — and would have turned any future
     * re-balancing of those weights into a failure nobody could read.</p>
     */
    @Test
    void rung30AVacationSpanningTheWholeMealWindow() {
        Loaded loaded = load("gamme-30-infaisable-coupure-repas");
        assertThat(loaded.feasibility().feasible()).isTrue();

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(solved.getScore().hardScore()).isNegative();
        assertThat(brokenHardConstraints(solved))
                .isNotEmpty()
                .isSubsetOf("posteDoitEtrePourvu", "coupureRepasObligatoire", "travailContinuMaxMajeur");

        Loaded sansCoupure = load("gamme-30-infaisable-coupure-repas");
        sansCoupure
                .problem()
                .setConstraintsDesactivees(List.of(
                        new ConstraintToggle("coupureRepasObligatoire"),
                        new ConstraintToggle("travailContinuMaxMajeur")));
        assertThat(solveUntilFeasible(sansCoupure, 30L).getScore().hardScore()).isZero();
    }
}
