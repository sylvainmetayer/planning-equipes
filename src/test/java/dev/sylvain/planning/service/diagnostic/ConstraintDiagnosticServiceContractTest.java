package dev.sylvain.planning.service.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.solver.PlanningConstraintProvider;

/**
 * The canary guarding a diagnostic built on Timefold's internals.
 *
 * <p>{@link ScoreDirectorConstraintDiagnosticService} replaces
 * {@code SolutionManager.analyze()} with the score director that method calls
 * internally, because {@code analyze()} is Enterprise-only from Timefold 2.x.
 * Reaching one layer lower means reaching into
 * {@code ai.timefold.solver.core.impl}, which carries no semver promise — so
 * the two implementations are run side by side here and asserted to agree,
 * down to the justification facts of every match.</p>
 *
 * <p>When a Timefold bump changes what the score director reports, this test
 * fails and names the difference. Without it the drift would surface as five
 * screens quietly showing something else. That is the whole reason
 * {@link SolutionManagerConstraintDiagnosticService} is kept after ceasing to
 * be the default: it is the oracle, not a fallback.</p>
 *
 * <p>Two plannings, deliberately: an empty one exercises the constraints that
 * match nothing (the "contraintes respectées" half of the per-assignment
 * explanation is built from exactly those, so an implementation dropping them
 * would pass on violations alone), and a violating one exercises the facts.</p>
 */
class ConstraintDiagnosticServiceContractTest {

    private static final SolverFactory<PlanningEvenement> SOLVER_FACTORY = solverFactory();

    private final ConstraintDiagnosticService viaSolutionManager =
            new SolutionManagerConstraintDiagnosticService(SOLVER_FACTORY);
    private final ConstraintDiagnosticService viaScoreDirector =
            new ScoreDirectorConstraintDiagnosticService(SOLVER_FACTORY);

    /**
     * Whether the oracle can run at all here. From Timefold 2.x,
     * {@code SolutionManager.analyze()} is Enterprise-gated, so on a Community
     * build there is nothing to compare against.
     */
    private static final boolean ORACLE_AVAILABLE = oracleAvailable();

    /**
     * Skipped rather than tagged out of the build, deliberately.
     *
     * <p>This comparison is the only thing watching a diagnostic built on
     * {@code ai.timefold.solver.core.impl}. Losing it to an Enterprise gate is a
     * real cost, and a cost should stay <b>visible</b>: a skipped test is
     * reported as skipped, run after run, where a {@code @Tag} excluded from
     * every build would simply vanish from the surefire report and be forgotten.
     * It also means the comparison comes back by itself the day a licence is
     * present, with no build configuration to remember.</p>
     */
    @BeforeEach
    void skipWhenTheOracleIsEnterpriseGated() {
        assumeTrue(ORACLE_AVAILABLE,
                "SolutionManager.analyze() is Enterprise-gated in this Timefold edition:"
                        + " there is no reference to compare the score director against.");
    }

    private static boolean oracleAvailable() {
        try {
            new SolutionManagerConstraintDiagnosticService(SOLVER_FACTORY)
                    .analyze(planningWithoutViolations());
            return true;
        } catch (RuntimeException enterpriseGated) {
            return false;
        }
    }

    @Test
    void bothImplementationsReportTheSameScoreAndConstraints() {
        PlanningEvenement planning = planningWithViolations();

        PlanningAnalysis expected = viaSolutionManager.analyze(planning);
        PlanningAnalysis actual = viaScoreDirector.analyze(planning);

        assertThat(actual.score()).isEqualTo(expected.score());
        assertThat(namesOf(actual)).isEqualTo(namesOf(expected));
    }

    @Test
    void bothImplementationsReportTheSameMatchesAndFacts() {
        PlanningEvenement planning = planningWithViolations();

        PlanningAnalysis expected = viaSolutionManager.analyze(planning);
        PlanningAnalysis actual = viaScoreDirector.analyze(planning);

        for (ConstraintContribution expectedContribution : expected.contributions()) {
            ConstraintContribution actualContribution = contribution(actual, expectedContribution.constraintName());
            assertThat(actualContribution.score())
                    .describedAs("score of %s", expectedContribution.constraintName())
                    .isEqualTo(expectedContribution.score());
            assertThat(actualContribution.matchCount())
                    .describedAs("match count of %s", expectedContribution.constraintName())
                    .isEqualTo(expectedContribution.matchCount());
            // Facts compared by identity through the domain objects' own
            // equality, and order-insensitively: the score director hands back
            // a Set, analyze() a List, and nothing downstream depends on the
            // order of matches within one constraint.
            assertThat(factsOf(actualContribution))
                    .describedAs("facts of %s", expectedContribution.constraintName())
                    .containsExactlyInAnyOrderElementsOf(factsOf(expectedContribution));
        }
    }

    /**
     * A constraint nobody violated must still be listed by both, or the
     * "contraintes respectées" list silently shrinks — a regression that shows
     * up as a screen with less on it, never as an error.
     */
    @Test
    void bothImplementationsListConstraintsThatMatchedNothing() {
        PlanningEvenement planning = planningWithoutViolations();

        PlanningAnalysis expected = viaSolutionManager.analyze(planning);
        PlanningAnalysis actual = viaScoreDirector.analyze(planning);

        assertThat(namesOf(actual)).isEqualTo(namesOf(expected));
        assertThat(actual.contributions()).anySatisfy(contribution -> {
            assertThat(contribution.matchCount()).isZero();
            assertThat(contribution.matches()).isEmpty();
        });
    }

    /**
     * Both implementations score the caller's own object graph rather than a
     * clone: the per-assignment explanation keeps the matches whose facts
     * contain <em>this</em> poste, compared with {@code ==}.
     */
    @Test
    void bothImplementationsJustifyWithTheCallersOwnObjects() {
        PlanningEvenement planning = planningWithViolations();
        PosteAffectation poste = planning.getPostes().get(0);

        assertThat(allFacts(viaSolutionManager.analyze(planning)))
                .describedAs("via SolutionManager").anyMatch(fact -> fact == poste);
        assertThat(allFacts(viaScoreDirector.analyze(planning)))
                .describedAs("via score director").anyMatch(fact -> fact == poste);
    }

    private static List<String> namesOf(PlanningAnalysis analysis) {
        return analysis.contributions().stream()
                .map(ConstraintContribution::constraintName)
                .sorted()
                .toList();
    }

    private static ConstraintContribution contribution(PlanningAnalysis analysis, String constraintName) {
        return analysis.contributions().stream()
                .filter(candidate -> candidate.constraintName().equals(constraintName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Constraint absent from the analysis: " + constraintName));
    }

    /** One comparable line per match, so a mismatch reads as text rather than as object identities. */
    private static List<String> factsOf(ConstraintContribution contribution) {
        return contribution.matches().stream()
                .map(match -> match.facts().stream()
                        .map(ConstraintDiagnosticServiceContractTest::describe)
                        .collect(Collectors.joining("|")))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * Names a fact the way the diagnostic screens read it: by which domain
     * object it is.
     *
     * <p>Not every fact is a domain object. {@code equilibrerCharge} justifies
     * itself with Timefold's load-balance accumulator, an internal value
     * rebuilt from scratch by each analysis and carrying no identity — two runs
     * of the <em>same</em> implementation would already disagree on it. Its
     * type is all two analyses can be held to, and it is also all anything
     * downstream ever reads: per-match facts are only formatted and searched
     * for hard constraints, and this is not one. The constraint's score and
     * match count are compared like every other's.</p>
     */
    private static String describe(Object fact) {
        return switch (fact) {
            case null -> "null";
            case Collection<?> collection -> collection.stream()
                    .map(ConstraintDiagnosticServiceContractTest::describe)
                    .collect(Collectors.joining(",", "[", "]"));
            case Animateur animateur -> "Animateur:" + animateur.getId();
            case PosteAffectation poste -> "Poste:" + poste.getId();
            case Creneau creneau -> "Creneau:" + creneau.getId();
            case Stand stand -> "Stand:" + stand.getId();
            case ContrainteAdHoc contrainte -> "ContrainteAdHoc:" + contrainte.getId();
            default -> fact.getClass().getName();
        };
    }

    private static List<Object> allFacts(PlanningAnalysis analysis) {
        return analysis.contributions().stream()
                .flatMap(contribution -> contribution.matches().stream())
                .flatMap(match -> match.facts().stream())
                .toList();
    }

    /**
     * A staffed seat whose ad hoc exception cannot be honoured, plus an
     * unfilled one: enough to make several hard constraints match, with facts
     * of every shape the formatter knows (animateur, poste, ad hoc rule).
     */
    private static PlanningEvenement planningWithViolations() {
        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur bob = new Animateur("A2", "Bob", "Durand", LocalDate.of(1990, 1, 1), false);
        PosteAffectation staffed = new PosteAffectation("P1", stand, creneau);
        staffed.setAnimateur(bob);
        PosteAffectation unfilled = new PosteAffectation("P2", stand, creneau);
        ContrainteAdHoc forcee = new ContrainteAdHoc("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        forcee.setAnimateursConcernes(List.of(alice));
        forcee.setCreneau(creneau);
        forcee.setRaison("Promesse faite en juin");
        return new PlanningEvenement(creneau.getDate(), List.of(alice, bob),
                List.of(staffed, unfilled), List.of(forcee));
    }

    private static PlanningEvenement planningWithoutViolations() {
        Stand stand = new Stand("STAND-1", "Stand tir à l'arc", Set.of(), 1, 1, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 7, 16), LocalTime.of(12, 30), LocalTime.of(15, 30));
        Animateur alice = new Animateur("A1", "Alice", "Martin", LocalDate.of(1990, 1, 1), false);
        PosteAffectation staffed = new PosteAffectation("P1", stand, creneau);
        staffed.setAnimateur(alice);
        return new PlanningEvenement(creneau.getDate(), List.of(alice), List.of(staffed));
    }

    /** The solver configuration {@code PlanningService} builds, minus its termination. */
    private static SolverFactory<PlanningEvenement> solverFactory() {
        SolverConfig solverConfig = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        solverConfig.setScoreDirectorFactoryConfig(new ScoreDirectorFactoryConfig()
                .withConstraintProviderClass(PlanningConstraintProvider.class));
        return SolverFactory.create(solverConfig);
    }
}
