package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.ProblemBuilder.StatistiquesIncremental;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
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
    private static final PastHorizon MIDI = new PastHorizon(AUJOURDHUI, LocalTime.of(12, 0));

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
        assertThat(FrozenPast.isPast(poste("p", nuitHier), new PastHorizon(AUJOURDHUI, LocalTime.of(1, 0))))
                .isTrue();
    }

    @Test
    void todayIsPastOnceItsStartIsReachedAndAheadBefore() {
        assertThat(FrozenPast.isPast(poste("p", matinAujourdhui), MIDI)).isTrue();
        assertThat(FrozenPast.isPast(poste("p", apremAujourdhui), MIDI)).isFalse();
        // At the very minute the timeslot starts, it has started.
        assertThat(FrozenPast.isPast(poste("p", apremAujourdhui), new PastHorizon(AUJOURDHUI, LocalTime.of(14, 0))))
                .isTrue();
    }

    @Test
    void tomorrowIsAheadWhateverTheHour() {
        assertThat(FrozenPast.isPast(poste("p", matinDemain), new PastHorizon(AUJOURDHUI, LocalTime.of(23, 59))))
                .isFalse();
    }

    @Test
    void aTimeslotCrossingMidnightBelongsToItsStartDate() {
        Creneau nuitAujourdhui = new Creneau(7L, 2, AUJOURDHUI, LocalTime.of(20, 0), LocalTime.of(2, 0));
        // 19:00 today: tonight's shift has not started, yesterday's night has.
        PastHorizon soir = new PastHorizon(AUJOURDHUI, LocalTime.of(19, 0));
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

    /**
     * The locks run first and the freeze second, on the same persisted plan:
     * a lock on Bob pins his seat, the seat Alice held is cleared again by
     * the lock pass (unlocked seats restart from scratch) and the freeze then
     * hands it back to her — the past re-seeded positionally, the lock left
     * exactly as it was. Built through the real lock path, because a state
     * where a lock holds somebody at a place the plan gives to somebody else
     * is one {@code seedFromAffectations} cannot produce: it re-seeds from the
     * very same plan the freeze walks.
     */
    @Test
    void aPastSeatTheLocksAlreadyPinnedIsLeftAsTheyLeftIt() {
        List<PosteAffectation> postes = List.of(poste("p0", matinHier), poste("p1", matinHier));
        Map<String, List<String>> plan = Map.of(key("STAND-A", 1L), List.of("A1", "A2"));
        VerrouillagePlanning verrouBob = new VerrouillagePlanning("V1", TypeVerrouillage.ANIMATEUR);
        verrouBob.setAnimateurId("A2");

        ProblemBuilder.applyVerrouillages(postes, animateurs, List.of(verrouBob), plan);
        assertThat(postes.get(0).getAnimateur()).isNull();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(1).isVerrouille()).isTrue();

        int passes = FrozenPast.freeze(postes, animateurs, plan, MIDI);

        assertThat(passes).isEqualTo(2);
        assertThat(postes.get(0).getAnimateur()).isEqualTo(alice);
        assertThat(postes.get(0).isPasse()).isTrue();
        assertThat(postes.get(0).isVerrouille()).isTrue();
        assertThat(postes.get(1).getAnimateur()).isEqualTo(bob);
        assertThat(postes.get(1).isPasse()).isTrue();
        assertThat(postes.get(1).isVerrouille()).isTrue();
    }

    /* ------------------------------ the gestures ------------------------------ */

    /**
     * « Le passé ne se modifie plus » : every manual write on a past seat —
     * the drag-and-drop, an échange, a repair applied or suggested — is
     * refused in the same words, and the same gesture on a seat still ahead
     * goes through. With the freeze off, nothing is refused.
     */
    @Test
    void everyManualWriteOnAPastSeatIsRefusedAndNoneWhenTheFreezeIsOff() {
        PosteAffectation hierAlice = poste("p0", matinHier);
        hierAlice.setAnimateur(alice);
        PosteAffectation hierBob = poste("p1", matinHier);
        hierBob.setAnimateur(bob);
        PosteAffectation demainAlice = poste("p2", matinDemain);
        demainAlice.setAnimateur(alice);
        PosteAffectation demainVide = poste("p3", matinDemain);
        PlanningEvenement plan = new PlanningEvenement(
                HIER, new ArrayList<>(animateurs), List.of(hierAlice, hierBob, demainAlice, demainVide));
        plan.setParametresLegaux(List.of(new ParametresLegaux()));
        PlanningWhatIf gele = whatIf(() -> MIDI);

        assertThatThrownBy(() -> gele.simulateDeplacement(plan, "p0", null, "A2"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        assertThatThrownBy(() -> gele.simulateDeplacement(plan, "p2", "p0", null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        assertThatThrownBy(() -> gele.simulateEchange(plan, "A1", "A2", 1L, "STAND-A"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        assertThatThrownBy(() -> gele.simulateDirectedEchange(plan, "A1", "A2", 6L, "STAND-A", 1L, "STAND-A"))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        assertThatThrownBy(() -> gele.suggererReparations(plan, "p1", null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        assertThatThrownBy(() -> gele.applyReparations(plan, List.of("p3", "p0"), null))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.PAST_SEAT_REFUSAL);
        // Tomorrow is still the operator's: the same gestures go through.
        assertThat(gele.simulateDeplacement(plan, "p2", "p3", null).posteCibleId())
                .isEqualTo("p3");
        assertThat(gele.suggererReparations(plan, "p3", null).posteId()).isEqualTo("p3");

        PlanningWhatIf libre = whatIf(() -> null);
        assertThat(libre.simulateDeplacement(plan, "p0", null, "A2").animateurCibleId())
                .isEqualTo("A2");
        assertThat(libre.suggererReparations(plan, "p1", null).posteId()).isEqualTo("p1");
    }

    private static PlanningWhatIf whatIf(java.util.function.Supplier<PastHorizon> horizon) {
        return new PlanningWhatIf(
                configuration().diagnosticService(), new EmptyReferenceData(), null, planning -> {}, horizon);
    }

    /* ------------------------------ nothing ahead ------------------------------ */

    /** A problem whose every seat is past has nothing to plan; one seat ahead, or no seat at all, is not that. */
    @Test
    void aProblemWithEverySeatPastIsRefusedAndOneSeatAheadIsEnough() {
        PosteAffectation hier = poste("p0", matinHier);
        PosteAffectation demain = poste("p1", matinDemain);
        FrozenPast.mark(List.of(hier, demain), MIDI);

        assertThatThrownBy(() -> FrozenPast.refuseIfNothingAhead(List.of(hier)))
                .isInstanceOf(BusinessError.Invalid.class)
                .hasMessage(FrozenPast.NOTHING_AHEAD_REFUSAL);
        assertThatCode(() -> FrozenPast.refuseIfNothingAhead(List.of(hier, demain)))
                .doesNotThrowAnyException();
        assertThatCode(() -> FrozenPast.refuseIfNothingAhead(List.of())).doesNotThrowAnyException();
    }

    @Test
    void thePastSeatsHoldingNobodyAreCountedAsAWarning() {
        PosteAffectation hierTenu = poste("p0", matinHier);
        hierTenu.setAnimateur(alice);
        PosteAffectation hierTrou = poste("p1", matinHier);
        PosteAffectation demainTrou = poste("p2", matinDemain);
        FrozenPast.mark(List.of(hierTenu, hierTrou, demainTrou), MIDI);

        // Only the past hole: tomorrow's is a seat to fill, not history.
        assertThat(FrozenPast.countEmptyPast(List.of(hierTenu, hierTrou, demainTrou)))
                .isEqualTo(1);
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
        // Three past seats, one of them a hole: the warning the recap shows.
        assertThat(stats).isEqualTo(new StatistiquesIncremental(4, 1, 0, 0, 0, 3, 1));
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

    /* ------------------------------ one horizon ------------------------------ */

    /**
     * The clock keeps moving between the build and the solve. A seat still
     * ahead when the problem was built — re-opened by the perimeter, or empty
     * on a cold start — must not be pinned empty by a preparation reading a
     * later clock: the preparation marks against the horizon the problem
     * carries, and pins only what that horizon marked.
     */
    @Test
    void thePreparationReadsTheHorizonTheProblemWasBuiltUnderNotAFreshClock() {
        PastHorizon auBuild = new PastHorizon(AUJOURDHUI, LocalTime.of(11, 0));
        PosteAffectation apresMidi = poste("p0", apremAujourdhui);
        PlanningEvenement problem = new PlanningEvenement(AUJOURDHUI, new ArrayList<>(animateurs), List.of(apresMidi));
        FrozenPast.mark(problem.getPostes(), auBuild);
        problem.setPastHorizon(auBuild);
        SolveRunner runner = new SolveRunner(
                configuration(),
                new EmptyReferenceData(),
                null,
                () -> new PastHorizon(AUJOURDHUI, LocalTime.of(14, 30)));

        runner.prepareProblem(problem);
        FrozenPast.pin(problem.getPostes());

        assertThat(apresMidi.isPasse()).isFalse();
        assertThat(apresMidi.isVerrouille()).isFalse();
        assertThat(problem.getPastHorizon()).isEqualTo(auBuild);
    }

    /** A problem that came without a horizon — a caller's body, the persisted plan — gets the clock's, once. */
    @Test
    void aProblemWithoutAHorizonReceivesTheClockAndKeepsIt() {
        PosteAffectation matin = poste("p0", matinAujourdhui);
        PlanningEvenement problem = new PlanningEvenement(AUJOURDHUI, new ArrayList<>(animateurs), List.of(matin));
        List<PastHorizon> lectures = List.of(MIDI, new PastHorizon(DEMAIN, LocalTime.of(9, 0)));
        int[] appels = {0};
        SolveRunner runner =
                new SolveRunner(configuration(), new EmptyReferenceData(), null, () -> lectures.get(appels[0]++));

        runner.prepareProblem(problem);
        runner.prepareProblem(problem);

        assertThat(appels[0]).isEqualTo(1);
        assertThat(problem.getPastHorizon()).isEqualTo(MIDI);
        assertThat(matin.isPasse()).isTrue();
    }

    private static SolverConfiguration configuration() {
        return new SolverConfiguration(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                ConfigProvider.getConfig());
    }

    /* ------------------------------ full assert ------------------------------ */

    /**
     * The whole constraint set under {@code FULL_ASSERT}, on a problem carrying
     * past seats — a held one, a pinned hole — next to seats still ahead:
     * Timefold recomputes the score from scratch after every move and refuses
     * any incremental drift, which is where a mistake around the {@code passe}
     * flag (a group folded wrong, an {@code ifExists} out of step) would show.
     */
    @Test
    void theConstraintsHoldUnderFullAssertWithPastSeatsInTheProblem() {
        PosteAffectation hierTenu = poste("p0", matinHier);
        hierTenu.setAnimateur(alice);
        PosteAffectation hierTrou = poste("p1", matinHier);
        PosteAffectation ceMatin = poste("p2", matinAujourdhui);
        ceMatin.setAnimateur(bob);
        PlanningEvenement problem = new PlanningEvenement(
                HIER,
                new ArrayList<>(animateurs),
                List.of(hierTenu, hierTrou, ceMatin, poste("p3", apremAujourdhui), poste("p4", matinDemain)));
        problem.setParametresLegaux(List.of(new ParametresLegaux()));
        FrozenPast.mark(problem.getPostes(), MIDI);
        FrozenPast.pin(problem.getPostes());
        problem.setPastHorizon(MIDI);
        new PlanningService(
                        3L,
                        2L,
                        ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                        new EmptyReferenceData(),
                        new FeasibilityAnalyzer(),
                        null,
                        null,
                        ConfigProvider.getConfig())
                .prepareForAnalysis(problem);
        SolverConfig config = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        config.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        config.setEnvironmentMode(EnvironmentMode.FULL_ASSERT);
        config.setTerminationConfig(new TerminationConfig().withSecondsSpentLimit(3L));

        PlanningEvenement solved =
                SolverFactory.<PlanningEvenement>create(config).buildSolver().solve(problem);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes().get(0).getAnimateur()).isEqualTo(alice);
        assertThat(solved.getPostes().get(1).getAnimateur()).isNull();
        assertThat(solved.getPostes().get(3).getAnimateur()).isNotNull();
        assertThat(solved.getPostes().get(4).getAnimateur()).isNotNull();
    }
}
