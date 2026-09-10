package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The other canary of this package, and the one issue #303 rests on.
 *
 * <p>{@link ConstraintDiagnosticService#hypotheses} has two implementations
 * for the same question: the obvious one, a full
 * {@link ConstraintDiagnosticService#analyze} per candidate, and the fast one
 * in {@link ScoreDirectorConstraintDiagnosticService}, which steps a single
 * score director from candidate to candidate the way local search steps from
 * move to move. The fast path is what makes the « banc de touche » screen
 * affordable for ~150 animateurs; being fast is worth nothing if it answers
 * something else, so the two are run side by side here.</p>
 *
 * <p>Unlike {@link ConstraintDiagnosticServiceContractTest}, whose oracle is
 * Enterprise-gated and therefore skipped on a Community build, both sides of
 * this comparison run on Community — so this one never turns into a green
 * build that proves nothing.</p>
 *
 * <p>And it watches for the failure mode a stepped score director actually
 * has: score corruption. Every substitution goes through
 * {@code beforeVariableChanged}/{@code afterVariableChanged}; forget one half
 * and the totals drift silently. Hence the assertion that the plan comes back
 * both untouched and scoring exactly what it did before.</p>
 */
class AffectationHypothesisTest {

    private static final SolverFactory<PlanningEvenement> SOLVER_FACTORY = solverFactory();

    private final ScoreDirectorConstraintDiagnosticService rapide =
            new ScoreDirectorConstraintDiagnosticService(SOLVER_FACTORY);

    /**
     * The naive implementation: a {@link ConstraintDiagnosticService} that only
     * knows how to analyze, so calling {@code hypotheses} on it runs the
     * interface's default — one full analysis per candidate.
     */
    private final ConstraintDiagnosticService naif = rapide::analyze;

    @Test
    void theFastPathAnswersExactlyWhatAFullAnalysisPerCandidateWould() {
        Fixture fixture = new Fixture();

        List<AffectationHypothesis> attendu = naif.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);
        List<AffectationHypothesis> obtenu = rapide.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);

        assertThat(obtenu).isEqualTo(attendu);
    }

    /**
     * Vacuity guard: the comparison above is worthless if every candidate comes
     * back with the same empty verdict.
     */
    @Test
    void theFixtureSeparatesCandidatesRatherThanReportingTheSameThingForAll() {
        Fixture fixture = new Fixture();

        List<AffectationHypothesis> hypotheses =
                rapide.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);

        assertThat(hypotheses).anyMatch(AffectationHypothesis::degradesHardScore);
        assertThat(hypotheses).anyMatch(hypothese -> !hypothese.degradesHardScore());
        assertThat(hypotheses)
                .anyMatch(hypothese -> !hypothese.contraintesAggravees().isEmpty());
    }

    /** The reasons are constraint names, taken from the rules — never invented here. */
    @Test
    void theReasonsNameTheConstraintThatRefuses() {
        Fixture fixture = new Fixture();

        List<AffectationHypothesis> hypotheses =
                rapide.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);

        assertThat(hypothese(hypotheses, "A-INDISPONIBLE").contraintesAggravees())
                .contains("animateurDisponible");
        assertThat(hypothese(hypotheses, "A-OCCUPE").contraintesAggravees()).contains("pasDeChevauchementHoraire");
        assertThat(hypothese(hypotheses, "A-LIBRE").contraintesAggravees())
                .doesNotContain("animateurDisponible", "pasDeChevauchementHoraire");
        assertThat(hypothese(hypotheses, "A-LIBRE").degradesHardScore()).isFalse();
    }

    @Test
    void steppingThroughEveryCandidateLeavesThePlanAndItsScoreUntouched() {
        Fixture fixture = new Fixture();
        Animateur occupantInitial = fixture.siegeLibre.getAnimateur();
        PlanningAnalysis avant = rapide.analyze(fixture.planning);

        rapide.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);

        assertThat(fixture.siegeLibre.getAnimateur()).isSameAs(occupantInitial);
        assertThat(rapide.analyze(fixture.planning).score()).isEqualTo(avant.score());
    }

    /**
     * Restoring the variable is not restoring the plan. Both implementations
     * write the score onto the solution as they go, so without a final
     * recalculation the caller gets its planning back carrying the <em>last
     * candidate's</em> score — indistinguishable from a real one, and a trap
     * for anything reading {@code getScore()} afterwards.
     *
     * <p>Read straight off the solution on purpose: calling {@code analyze()}
     * again is what hid this, because it recomputes the very field under test.
     * And probed on the <b>free</b> seat, because the state to come back to —
     * unfilled, so one {@code posteDoitEtrePourvu} short — is one no candidate
     * can score the same as. On an occupied seat two interchangeable adults
     * score alike and a missing recalculation slips through unnoticed.</p>
     */
    @Test
    void bothImplementationsLeaveAFreshScoreOnTheSolution() {
        for (ConstraintDiagnosticService implementation : List.of(rapide, naif)) {
            Fixture fixture = new Fixture();
            HardMediumSoftScore attendu =
                    implementation.analyze(fixture.planning).score();

            implementation.hypotheses(fixture.planning, fixture.siegeLibre, fixture.candidats);

            assertThat(fixture.planning.getScore())
                    .describedAs(
                            "score left on the solution by %s",
                            implementation.getClass().getSimpleName())
                    .isEqualTo(attendu);
        }
    }

    /**
     * The baseline is the seat empty, not its occupant — the fix for the bug
     * that made this screen call somebody « disponible » while they were on
     * duty elsewhere at that hour.
     *
     * <p>Probing an occupied seat with its own occupant must therefore report a
     * cost, not nothing: they do fill a seat the baseline leaves empty.</p>
     */
    @Test
    void theBaselineIsTheSeatEmptyEvenWhenItIsTaken() {
        Fixture fixture = new Fixture();
        Animateur titulaire = fixture.siegeOccupe.getAnimateur();

        List<AffectationHypothesis> hypotheses =
                rapide.hypotheses(fixture.planning, fixture.siegeOccupe, List.of(titulaire));

        // posteDoitEtrePourvu is settled by putting them back: that is a gain,
        // so the baseline really was the empty seat and not the plan as given.
        assertThat(hypotheses)
                .singleElement()
                .satisfies(
                        hypothese -> assertThat(hypothese.delta().hardScore()).isPositive());
    }

    /**
     * The seat probed need not be empty: handing an occupied one to someone
     * else is the « qui pourrait le remplacer ? » question, and the two
     * implementations must agree on it too — including on the constraints the
     * departing occupant <em>stops</em> violating, which must not be reported
     * as reasons.
     */
    @Test
    void theTwoImplementationsAlsoAgreeOnReplacingAnOccupant() {
        Fixture fixture = new Fixture();

        assertThat(rapide.hypotheses(fixture.planning, fixture.siegeOccupe, fixture.candidats))
                .isEqualTo(naif.hypotheses(fixture.planning, fixture.siegeOccupe, fixture.candidats));
    }

    private static AffectationHypothesis hypothese(List<AffectationHypothesis> hypotheses, String animateurId) {
        return hypotheses.stream()
                .filter(hypothese -> hypothese.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucune hypothèse pour " + animateurId));
    }

    /**
     * One day, two overlapping créneaux, and four candidates picked so that the
     * same seat is judged four different ways: unavailable that day, already on
     * an overlapping créneau, a minor with no adult beside them, and nothing at
     * all against them. A fifth animateur holds the occupied seat, so that
     * probing it is a genuine replacement.
     */
    private static final class Fixture {

        private final PlanningEvenement planning;
        private final PosteAffectation siegeLibre;
        private final PosteAffectation siegeOccupe;
        private final List<Animateur> candidats;

        private Fixture() {
            LocalDate jour = LocalDate.of(2026, 7, 16);
            Stand standTousPublics = new Stand("S1", "Chamboule-tout", Set.of(), 1, 2, false);
            Stand standMajeurs = new Stand("S2", "Bar", Set.of(), 1, 1, true);
            Creneau matin = new Creneau(1L, 1, jour, LocalTime.of(10, 0), LocalTime.of(13, 0));
            Creneau chevauchant = new Creneau(2L, 1, jour, LocalTime.of(12, 0), LocalTime.of(15, 0));

            Animateur libre = new Animateur("A-LIBRE", "Léa", "Martin", LocalDate.of(1990, 1, 1), false);
            Animateur indisponible = new Animateur("A-INDISPONIBLE", "Ana", "Durand", LocalDate.of(1990, 1, 1), false);
            indisponible.setJoursIndisponibles(Set.of(jour));
            Animateur occupe = new Animateur("A-OCCUPE", "Omar", "Bernard", LocalDate.of(1990, 1, 1), false);
            Animateur mineur = new Animateur("A-MINEUR", "Manon", "Petit", jour.minusYears(15), false);
            Animateur titulaire = new Animateur("A-TITULAIRE", "Théo", "Roux", LocalDate.of(1990, 1, 1), false);

            siegeLibre = new PosteAffectation("P-LIBRE", standTousPublics, matin);
            siegeOccupe = new PosteAffectation("P-OCCUPE", standMajeurs, matin);
            siegeOccupe.setAnimateur(titulaire);
            PosteAffectation ailleurs = new PosteAffectation("P-AILLEURS", standTousPublics, chevauchant);
            ailleurs.setAnimateur(occupe);

            candidats = List.of(indisponible, mineur, occupe, libre);
            planning = new PlanningEvenement(
                    jour,
                    List.of(libre, indisponible, occupe, mineur, titulaire),
                    List.of(siegeLibre, siegeOccupe, ailleurs));
        }
    }

    /** The solver configuration {@code PlanningService} builds, minus its termination. */
    private static SolverFactory<PlanningEvenement> solverFactory() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        return SolverFactory.create(solverConfig);
    }
}
