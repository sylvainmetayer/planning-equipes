package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.ConstraintFloorRules.Denominator;
import dev.sylvain.planning.solver.ConstraintFloorRules.MissingData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The floor table is only worth something if it is complete: a medium or soft
 * rule added to {@link ConstraintCatalog} without a line in
 * {@link ConstraintFloorRules} would be the one rule whose floor nobody sees —
 * exactly the blind spot of issue #495. The first two tests are that
 * structural guard; the others pin the grain of each denominator on a plan
 * small enough to count by hand.
 */
class ConstraintFloorRulesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 8);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 9);

    @Test
    void everyNonHardConstraintOfTheCatalogueHasAFloorRule() {
        List<String> nonHard = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() != ConstraintCatalog.Niveau.HARD)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .toList();

        assertThat(nonHard)
                .isNotEmpty()
                .as("a medium/soft constraint without a floor rule cannot be read as a floor")
                .allSatisfy(name ->
                        assertThat(ConstraintFloorRules.of(name)).as(name).isNotNull());
    }

    @Test
    void noFloorRuleNamesAHardOrUnknownConstraint() {
        Set<String> nonHard = ConstraintCatalog.definitions().stream()
                .filter(definition -> definition.niveau() != ConstraintCatalog.Niveau.HARD)
                .map(ConstraintCatalog.ConstraintDefinition::name)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(ConstraintFloorRules.names()).isSubsetOf(nonHard);
        Set<String> hardNames = ConstraintCatalog.NOMS_DURS;
        assertThat(hardNames)
                .isNotEmpty()
                .allSatisfy(name -> assertThat(ConstraintFloorRules.of(name))
                        .as("a hard rule is never a floor: it is respected or the plan is invalid")
                        .isNull());
    }

    /**
     * Two animateurs, two stands (one premium), three timeslots over two days,
     * five seats of which one stays empty — every denominator counted by hand.
     */
    private static PlanningEvenement plan() {
        Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bob = new Animateur("A2", "Bob", "Durand", LocalDate.of(1990, 1, 1), false);
        Stand premium = new Stand("S1", "Stand premium", Set.of("STRAT"), 1, 2, false);
        premium.setPremium(true);
        Stand plain = new Stand("S2", "Stand ordinaire", Set.of("STRAT"), 1, 2, false);
        Creneau c1 = new Creneau(1L, 1, D1, LocalTime.of(9, 0), LocalTime.of(13, 0));
        Creneau c2 = new Creneau(2L, 1, D1, LocalTime.of(13, 0), LocalTime.of(17, 0));
        Creneau c3 = new Creneau(3L, 2, D2, LocalTime.of(9, 0), LocalTime.of(13, 0));
        PosteAffectation p1 = seat("P1", premium, c1, alice);
        PosteAffectation p2 = seat("P2", plain, c1, bob);
        PosteAffectation p3 = seat("P3", premium, c2, alice);
        PosteAffectation p4 = seat("P4", plain, c2, null);
        PosteAffectation p5 = seat("P5", premium, c3, bob);
        return new PlanningEvenement(D1, List.of(alice, bob), List.of(p1, p2, p3, p4, p5));
    }

    private static PosteAffectation seat(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }

    /**
     * A rule whose match count is not a boolean per item has no per-item
     * reading, and the two shapes that are not are easy to add by accident: a
     * reward — whose score is positive, so counting it into the floor would
     * make the score net of the floor <em>worse</em> than the raw one — and a
     * penalty carrying a weight function, whose match count is a quantity of
     * excess. Both are named here so that giving one a denominator fails.
     */
    @Test
    void aRewardAndTheGradientRulesHaveNoPerItemReading() {
        assertThat(ConstraintFloorRules.of("affiniteAdHoc").denominator())
                .as("a reward is never a floor: its score is positive")
                .isEqualTo(Denominator.NONE);

        List<String> gradient = List.of(
                "repartitionMineursParCreneau",
                "eviterRoulementStandsPremium",
                "limiterEmplacementsParJour",
                "limiterTypologiesDistinctesParAnimateur",
                "maxJoursConsecutifsTravailles",
                "coupureRepasPlacementPrefere",
                "preserverBufferPolyvalents",
                "equilibrerCharge",
                "equilibrerCreneauxPenibles");
        assertThat(gradient)
                .isNotEmpty()
                .allSatisfy(name -> assertThat(ConstraintFloorRules.of(name).denominator())
                        .as("%s penalises by a magnitude: its match count is an excess, not an item", name)
                        .isEqualTo(Denominator.NONE));
    }

    @Test
    void countsEachDenominatorAtTheGrainOfItsRules() {
        PlanningEvenement plan = plan();

        assertThat(Denominator.FILLED_SEATS.count(plan)).isEqualTo(4);
        assertThat(Denominator.FILLED_PREMIUM_SEATS.count(plan)).isEqualTo(3);
        // S1×C1, S2×C1, S1×C2, S1×C3 — S2×C2 holds only an empty seat.
        assertThat(Denominator.STAFFED_STAND_CRENEAU_GROUPS.count(plan)).isEqualTo(4);
        // Alice, day 1: 9-13 then 13-17. Bob's two seats are on different days.
        assertThat(Denominator.CONSECUTIVE_PAIRS.count(plan)).isEqualTo(1);
        assertThat(Denominator.NONE.count(plan)).isNull();
    }

    @Test
    void publishedSeatsAreTheOnesOnALineThePublishedPlanHadEmptyOnesIncluded() {
        PlanningEvenement plan = plan();
        assertThat(Denominator.PUBLISHED_SEATS.count(plan)).isZero();

        plan.setAffectationsPubliees(List.of(
                new AffectationPubliee("S2", D1, LocalTime.of(13, 0), LocalTime.of(17, 0), "A9"),
                new AffectationPubliee("S1", D1, LocalTime.of(9, 0), LocalTime.of(13, 0), "A1")));

        // S2×C2 is the empty seat P4; S1×C1 is P1.
        assertThat(Denominator.PUBLISHED_SEATS.count(plan)).isEqualTo(2);
    }

    @Test
    void anEmptyPlanCountsNothingAndDividesNobody() {
        PlanningEvenement vide = new PlanningEvenement(D1, List.of(), List.of());

        for (Denominator denominator : Denominator.values()) {
            if (denominator != Denominator.NONE) {
                assertThat(denominator.count(vide)).as(denominator.name()).isZero();
            }
        }
    }

    @Test
    void missingDataIsReadFromTheAnimateursOfTheProblem() {
        PlanningEvenement plan = plan();

        assertThat(MissingData.SOUHAITS.absentFrom(plan)).isTrue();
        assertThat(MissingData.APPRECIATIONS.absentFrom(plan)).isTrue();
        assertThat(MissingData.REFERENTS.absentFrom(plan)).isTrue();
        assertThat(MissingData.NIVEAUX_COMPETENCE.absentFrom(plan)).isTrue();

        Animateur alice = plan.getAnimateurs().get(0);
        alice.setSouhaits(Set.of("STRAT"));
        alice.setCompetences(Map.of("STRAT", NiveauCompetence.AUTONOME));

        assertThat(MissingData.SOUHAITS.absentFrom(plan)).isFalse();
        assertThat(MissingData.APPRECIATIONS.absentFrom(plan)).isFalse();
        assertThat(MissingData.NIVEAUX_COMPETENCE.absentFrom(plan)).isFalse();
        // Autonomous is not referent: that one is still missing.
        assertThat(MissingData.REFERENTS.absentFrom(plan)).isTrue();
    }

    /** Each wording is shown on a card and over MCP: short, and pointing at a screen. */
    @Test
    void everyMissingDataNamesItsScreenInOneShortSentence() {
        for (MissingData missingData : MissingData.values()) {
            assertThat(missingData.libelle())
                    .as(missingData.name())
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(missingData.lien()).as(missingData.name()).startsWith("/");
        }
    }
}
