package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import dev.sylvain.planning.solver.constraints.QualiteConstraints;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The recap's « N places rendues » and the stability rule count the same
 * thing: {@link FeasibilityFirstSolve#publishedSeatsChanged} repeats the
 * filter of {@code stabiliteDuPlanPublie} (ADR 0067) rather than reading its
 * match count, so each case below scores the plan with the rule itself and
 * asks the count for the same number. A change to the rule that the count does
 * not follow fails here, instead of showing a number on the Solveur recap that
 * the rules screen contradicts.
 */
class PublishedSeatsChangedConcordanceTest {

    private static final String STABILITY = FeasibilityFirstSolve.STABILITY_RULE;

    private static final ConstraintVerifier<PlanningConstraintProvider, PlanningEvenement> CHECK =
            ConstraintVerifier.build(new PlanningConstraintProvider(), PlanningEvenement.class, PosteAffectation.class);

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private final Stand standA = new Stand("A", "Stand A", Set.of(), 1, 2, false);
    private final Stand standB = new Stand("B", "Stand B", Set.of(), 1, 2, false);
    private final Creneau morning = new Creneau(1L, 1, MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Creneau afternoon = new Creneau(2L, 1, MONDAY, LocalTime.of(13, 0), LocalTime.of(16, 0));
    private final Animateur x = new Animateur("X", "Xavier", "Un", LocalDate.of(1990, 1, 1), false);
    private final Animateur y = new Animateur("Y", "Yann", "Deux", LocalDate.of(1990, 1, 1), false);
    private final Animateur z = new Animateur("Z", "Zoé", "Trois", LocalDate.of(1990, 1, 1), false);

    private int seatSequence;

    private PosteAffectation seat(Stand stand, Creneau creneau, Animateur holder) {
        PosteAffectation poste = new PosteAffectation("P" + ++seatSequence, stand, creneau);
        poste.setAnimateur(holder);
        return poste;
    }

    private static AffectationPubliee published(Stand stand, Creneau creneau, Animateur holder) {
        return new AffectationPubliee(
                stand.getId(), creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin(), holder.getId());
    }

    /**
     * The recap's count is {@code expected}, and the rule scores the same plan
     * at the same number — pinned, so a rule gone silent cannot agree with a
     * count gone silent.
     */
    private static void assertSameCount(
            int expected, List<PosteAffectation> seats, List<AffectationPubliee> publication) {
        PlanningEvenement plan = new PlanningEvenement(MONDAY, List.of(), seats);
        plan.setAffectationsPubliees(publication);
        int counted = FeasibilityFirstSolve.publishedSeatsChanged(plan);
        assertThat(counted).isEqualTo(expected);

        List<Object> facts = new ArrayList<>(seats);
        facts.addAll(publication);
        CHECK.verifyThat(PublishedSeatsChangedConcordanceTest::stabilityRule)
                .given(facts.toArray())
                .penalizesBy(counted);
    }

    private static Constraint stabilityRule(PlanningConstraintProvider provider, ConstraintFactory factory) {
        return Stream.of(new QualiteConstraints().define(factory))
                .filter(constraint -> constraint.getConstraintRef().id().equals(STABILITY))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theHolderKeptCountsNothing() {
        assertSameCount(0, List.of(seat(standA, morning, x)), List.of(published(standA, morning, x)));
    }

    @Test
    void aReplacedHolderCountsOne() {
        assertSameCount(1, List.of(seat(standA, morning, y)), List.of(published(standA, morning, x)));
    }

    @Test
    void twoHoldersSwappingOnTheSameLineCountNothing() {
        assertSameCount(
                0,
                List.of(seat(standA, morning, y), seat(standA, morning, x)),
                List.of(published(standA, morning, x), published(standA, morning, y)));
    }

    @Test
    void twoHoldersSwappingAcrossLinesCountTwo() {
        assertSameCount(
                2,
                List.of(seat(standA, morning, y), seat(standB, morning, x)),
                List.of(published(standA, morning, x), published(standB, morning, y)));
    }

    @Test
    void aPublishedSeatLeftEmptyCountsOne() {
        assertSameCount(1, List.of(seat(standA, morning, null)), List.of(published(standA, morning, x)));
    }

    @Test
    void aNewcomerOnAnExtraSeatOfAPublishedLineCountsOne() {
        assertSameCount(
                1, List.of(seat(standA, morning, x), seat(standA, morning, z)), List.of(published(standA, morning, x)));
    }

    @Test
    void aLineThePublicationNeverHadCountsNothing() {
        assertSameCount(
                0,
                List.of(seat(standB, morning, y), seat(standA, afternoon, null)),
                List.of(published(standA, morning, x)));
    }

    @Test
    void aTimeslotRecreatedOnTheSameHoursIsStillThePublishedLine() {
        Creneau recreated = new Creneau(3L, 1, MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
        assertSameCount(1, List.of(seat(standA, recreated, y)), List.of(published(standA, morning, x)));
    }

    @Test
    void aPastSeatCountsNothing() {
        PosteAffectation past = seat(standA, morning, y);
        past.setPasse(true);
        assertSameCount(0, List.of(past), List.of(published(standA, morning, x)));
    }

    /** A seat split on the day (ADR 0066): the origin is past, its remainder held by somebody else. */
    @Test
    void theRemainderOfASplitSeatCountsLikeAnyOtherSeat() {
        PosteAffectation origin = seat(standA, morning, x);
        origin.setPasse(true);
        origin.setHeureFinEffective(LocalTime.of(10, 0));
        PosteAffectation remainder = seat(standA, morning, y);
        remainder.setSuiteDe(origin.getId());
        remainder.setHeureDebutEffective(LocalTime.of(10, 0));
        assertSameCount(1, List.of(origin, remainder), List.of(published(standA, morning, x)));
    }
}
