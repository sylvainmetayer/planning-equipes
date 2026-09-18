package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.FrozenPast.Horizon;
import dev.sylvain.planning.service.solve.ProblemBuilder.StatistiquesIncremental;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * « Le passé est figé » (ADR 0044) on the seats alone: which seats are past
 * under a horizon, what the freeze does to them in a full and in an
 * incremental problem, and the two guards around it — the perimeter never
 * re-opens the past, and Timefold accepts a pinned seat that holds nobody.
 */
class FrozenPastTest {

    private static final LocalDate HIER = LocalDate.of(2027, 7, 13);
    private static final LocalDate AUJOURDHUI = LocalDate.of(2027, 7, 14);
    private static final LocalDate DEMAIN = LocalDate.of(2027, 7, 15);
    private static final Horizon MIDI = new Horizon(AUJOURDHUI, LocalTime.of(12, 0));

    private final Stand standA = new Stand("STAND-A", "Stand A", Set.of("STRATEGIE"), 2, 2, false);
    private final Creneau matinHier = new Creneau(1L, 1, HIER, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Creneau nuitHier = new Creneau(2L, 1, HIER, LocalTime.of(20, 0), LocalTime.of(2, 0));
    private final Creneau matinAujourdhui = new Creneau(3L, 2, AUJOURDHUI, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Creneau apremAujourdhui = new Creneau(4L, 2, AUJOURDHUI, LocalTime.of(14, 0), LocalTime.of(18, 0));
    private final Creneau journeeAujourdhui = new Creneau(5L, 2, AUJOURDHUI, LocalTime.of(9, 0), LocalTime.of(18, 0));
    private final Creneau matinDemain = new Creneau(6L, 3, DEMAIN, LocalTime.of(9, 0), LocalTime.of(13, 0));
    private final Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(2000, 1, 1), false);
    private final Animateur bob = new Animateur("A2", "Bob", "Durand", LocalDate.of(2000, 1, 1), false);
    private final List<Animateur> animateurs = List.of(alice, bob);

    private static String key(String standId, long creneauId) {
        return PlanningPersistenceService.standCreneauKey(standId, creneauId);
    }

    private PosteAffectation poste(String id, Creneau creneau) {
        return new PosteAffectation(id, standA, creneau);
    }

    /* ------------------------------ isPast ------------------------------ */

    @Test
    void yesterdayIsPastWhateverTheHour() {
        assertThat(FrozenPast.isPast(poste("p", matinHier), MIDI)).isTrue();
        assertThat(FrozenPast.isPast(poste("p", nuitHier), new Horizon(AUJOURDHUI, LocalTime.of(1, 0))))
                .isTrue();
    }

    @Test
    void todayIsPastOnceItsStartIsReachedAndAheadBefore() {
        assertThat(FrozenPast.isPast(poste("p", matinAujourdhui), MIDI)).isTrue();
        assertThat(FrozenPast.isPast(poste("p", apremAujourdhui), MIDI)).isFalse();
        // At the very minute the timeslot starts, it has started.
        assertThat(FrozenPast.isPast(poste("p", apremAujourdhui), new Horizon(AUJOURDHUI, LocalTime.of(14, 0))))
                .isTrue();
    }

    @Test
    void tomorrowIsAheadWhateverTheHour() {
        assertThat(FrozenPast.isPast(poste("p", matinDemain), new Horizon(AUJOURDHUI, LocalTime.of(23, 59))))
                .isFalse();
    }

    @Test
    void aTimeslotCrossingMidnightBelongsToItsStartDate() {
        Creneau nuitAujourdhui = new Creneau(7L, 2, AUJOURDHUI, LocalTime.of(20, 0), LocalTime.of(2, 0));
        // 19:00 today: tonight's shift has not started, yesterday's night has.
        Horizon soir = new Horizon(AUJOURDHUI, LocalTime.of(19, 0));
        assertThat(FrozenPast.isPast(poste("p", nuitAujourdhui), soir)).isFalse();
        assertThat(FrozenPast.isPast(poste("p", nuitHier), soir)).isTrue();
    }

    @Test
    void theEffectiveStartOfTheSeatIsWhatCounts() {
        // A partial closure narrows the seat to 14:00-18:00 of a 9:00-18:00
        // timeslot: at noon the timeslot has started, the seat has not.
        PosteAffectation apresMidi = poste("p", journeeAujourdhui);
        apresMidi.setHeureDebutEffective(LocalTime.of(14, 0));
        apresMidi.setHeureFinEffective(LocalTime.of(18, 0));
        assertThat(FrozenPast.isPast(apresMidi, MIDI)).isFalse();
        assertThat(FrozenPast.isPast(poste("p", journeeAujourdhui), MIDI)).isTrue();
    }

    @Test
    void withoutAHorizonNothingIsPast() {
        assertThat(FrozenPast.isPast(poste("p", matinHier), null)).isFalse();
        assertThat(FrozenPast.mark(List.of(poste("p", matinHier)), null)).isZero();
    }

    /* ------------------------------ freeze ------------------------------ */

    @Test
    void thePastIsReseededFromThePersistedPlanAndPinnedTheFutureIsLeftAlone() {
        List<PosteAffectation> postes = List.of(
                poste("p0", matinHier), poste("p1", matinHier), poste("p2", matinAujourdhui), poste("p3", matinDemain));
        // Alice has since declared yesterday off: she was there all the same.
        alice.setJoursIndisponibles(Set.of(HIER));

        int passes = FrozenPast.freeze(
                postes,
                animateurs,
                Map.of(
                        key("STAND-A", 1L),
                        List.of("A1", "A2"),
                        key("STAND-A", 3L),
                        List.of("A2"),
                        key("STAND-A", 6L),
                        List.of("A1")),
                MIDI);

        assertThat(passes).isEqualTo(3);
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(0).isPasse()).isTrue();
        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(2).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(2).isVerrouille()).isTrue();
        // Tomorrow: not seeded here, not pinned, not past — the warm start's business.
        assertThat(postes.get(3).getAnimateur()).isNull();
        assertThat(postes.get(3).isPasse()).isFalse();
        assertThat(postes.get(3).isVerrouille()).isFalse();
    }

    @Test
    void aPastSeatWhoseHolderIsGoneOrThatWasNeverStaffedIsPinnedEmpty() {
        List<PosteAffectation> postes = List.of(poste("p0", matinHier), poste("p1", matinHier));

        int passes = FrozenPast.freeze(postes, animateurs, Map.of(key("STAND-A", 1L), List.of("DISPARU")), MIDI);

        assertThat(passes).isEqualTo(2);
        assertThat(postes).allSatisfy(poste -> {
            assertThat(poste.getAnimateur()).isNull();
            assertThat(poste.isPasse()).isTrue();
            assertThat(poste.isVerrouille()).isTrue();
        });
    }

    @Test
    void aPastSeatTheLocksAlreadyPinnedIsLeftAsTheyLeftIt() {
        PosteAffectation verrouille = poste("p0", matinHier);
        verrouille.setAnimateur(bob);
        verrouille.setVerrouille(true);
        List<PosteAffectation> postes = List.of(verrouille, poste("p1", matinHier));

        FrozenPast.freeze(postes, animateurs, Map.of(key("STAND-A", 1L), List.of("A1", "A2")), MIDI);

        // The lock said Bob on the first place; the freeze does not re-read
        // the plan over it, and the positional walk still gives Bob's
        // persisted place to the seat that follows.
        assertThat(postes.get(0).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(0).isPasse()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
    }

    /* --------------------------- incremental --------------------------- */

    @Test
    void theIncrementalReconciliationSettlesThePastBeforeItsOwnRules() {
        alice.setJoursIndisponibles(Set.of(HIER));
        ContrainteAdHoc indisponibilite = new ContrainteAdHoc("AH1", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        indisponibilite.setAnimateursConcernes(List.of(bob));
        indisponibilite.setCreneau(matinHier);
        List<PosteAffectation> postes = List.of(
                poste("p0", matinHier), poste("p1", matinHier), poste("p2", matinHier), poste("p3", matinDemain));

        StatistiquesIncremental stats = ProblemBuilder.figerPostesIncremental(
                postes,
                animateurs,
                Map.of(key("STAND-A", 1L), List.of("A1", "A2"), key("STAND-A", 6L), List.of("A1")),
                new ReplanificationScope(Set.of(), Set.of(HIER), Set.of()),
                List.of(indisponibilite),
                MIDI);

        // Yesterday: Alice kept although unavailable since, Bob kept although
        // a forced unavailability now covers him, the third seat pinned empty
        // — and none of the three re-opened by the perimeter naming that day.
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(2).getAnimateur()).isNull();
        assertThat(postes.subList(0, 3)).allSatisfy(poste -> {
            assertThat(poste.isPasse()).isTrue();
            assertThat(poste.isVerrouille()).isTrue();
        });
        // Tomorrow follows the ordinary rule: valid, hence pinned.
        assertThat(postes.get(3).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(3).isPasse()).isFalse();
        assertThat(stats).isEqualTo(new StatistiquesIncremental(4, 1, 0, 0, 0, 3));
    }

    @Test
    void thePerimeterNeverReleasesAPastSeat() {
        PosteAffectation passe = poste("p0", matinHier);
        passe.setPasse(true);
        ReplanificationScope tout = new ReplanificationScope(Set.of("A1"), Set.of(HIER), Set.of("STAND-A"));

        assertThat(tout.release(passe, "A1")).isFalse();
        assertThat(tout.release(poste("p1", matinHier), "A1")).isTrue();
    }

    /* ------------------------------ the solver ------------------------------ */

    /**
     * The one thing this rule leans on that is Timefold's, not ours: a pinned
     * entity whose planning variable is {@code null} is accepted, left alone,
     * and — {@code posteDoitEtrePourvu} reading the flag — not charged. A
     * solve on a two-seat problem says so, and reaches zero hard with the past
     * hole in place.
     */
    @Test
    void timefoldAcceptsAPinnedEmptySeatAndTheSolveReachesZeroHardAroundIt() {
        PosteAffectation trouHier = poste("p0", matinHier);
        trouHier.setPasse(true);
        trouHier.setVerrouille(true);
        PosteAffectation demain = poste("p1", matinDemain);
        PlanningEvenement problem = new PlanningEvenement(HIER, new ArrayList<>(animateurs), List.of(trouHier, demain));
        PlanningService planningService = new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());

        PlanningEvenement solved = planningService.solveUntilFeasible(problem, 10);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes().get(0).getAnimateur()).isNull();
        assertThat(solved.getPostes().get(0).isVerrouille()).isTrue();
        assertThat(solved.getPostes().get(1).getAnimateur()).isNotNull();
    }
}
