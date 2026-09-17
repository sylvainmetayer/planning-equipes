package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The hard-constraint invariant on the small nominal scenario: whatever the
 * solver does with the rest, a plan it returns breaks no hard rule.
 *
 * <p>Built by hand rather than injected (issue #475): the solve reads the
 * scenario file, the catalogue and the parameters an unconfigured edition
 * would return — never the database — so the test that AGENTS.md tells a
 * contributor to run on a new constraint now runs in {@code -Punit}, without
 * Docker or PostgreSQL. The two termination limits are the ones the
 * {@code %test} profile hands the application, so the budget is unchanged.</p>
 *
 * <p>The published-plan case had to change how it publishes, and gains from
 * it: {@code SolveRunner} overwrites {@code affectationsPubliees} with the
 * server's last publication before anything is scored, so the seats this test
 * used to set on the problem never reached the solver — it was reading
 * whatever the shared test database happened to hold. They are handed to the
 * service now, where a solve actually reads them.</p>
 */
class PlanningHardConstraintsTest {

    private final PlanningService planningService = service(null);

    /**
     * @param publication the last published plan, or {@code null} when nothing
     *                    was ever published — which is what a solve reads, and
     *                    never what its caller sent: {@code SolveRunner}
     *                    overwrites the field before scoring anything.
     */
    private static PlanningService service(PlanSnapshotService publication) {
        return new PlanningService(
                3L,
                2L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                publication,
                ConfigProvider.getConfig());
    }

    @Test
    void generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase() {
        PlanningEvenement problem = planningService.buildSimpleExample();

        PlanningEvenement solved = planningService.solve(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
    }

    /**
     * The stability rule (medium) must never buy stability with a hard
     * violation: published seats that today's rules forbid are left behind,
     * and the plan still comes out hard-feasible.
     */
    @Test
    void publishedSeatsNeverOutweighAHardRule() {
        PlanningEvenement problem = planningService.buildSimpleExample();
        // "Publish" a plan that puts everybody on the first seat's stand and
        // créneau at once — an impossible plan to keep. It has to come from the
        // publication the server holds, not from the problem: a plan a caller
        // sends is overwritten before it is scored.
        PosteAffectation premier = problem.getPostes().get(0);
        PlanningEvenement solved = service(publishedPlan(problem, premier)).solve(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
    }

    @Test
    void planningViolatesHardScoreWhenForcedAssignmentCannotBeSatisfied() {
        PlanningEvenement problem = planningService.buildSimpleExample();

        ContrainteAdHoc contrainte = new ContrainteAdHoc("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(problem.getAnimateurs().get(0)));
        contrainte.setCreneau(
                new Creneau(Long.MAX_VALUE, 10, LocalDate.now().plusDays(20), LocalTime.of(9, 0), LocalTime.of(12, 0)));
        contrainte.setStand(problem.getPostes().get(0).getStand());
        contrainte.setCreeLe(Instant.now());
        problem.setContraintesAdHoc(List.of(contrainte));

        PlanningEvenement solved = planningService.solve(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isLessThan(0);
    }

    /**
     * The publication a solve reads, without a database: every animateur on
     * the same seat. The snapshot carries no day or hours, as one captured
     * before issue #576 does, so {@code SolveRunner.factsPublies} names the
     * vacation from the créneau the id points at — the fields are left empty
     * rather than fabricated, and that fallback path gets exercised here.
     */
    private static PlanSnapshotService publishedPlan(PlanningEvenement problem, PosteAffectation seat) {
        List<PlanSnapshotService.AffectationSnapshot> affectations = problem.getAnimateurs().stream()
                .map(animateur -> new PlanSnapshotService.AffectationSnapshot(
                        null,
                        seat.getStand().getId(),
                        String.valueOf(seat.getCreneau().getId()),
                        null,
                        null,
                        null,
                        animateur.getId(),
                        null,
                        null))
                .toList();
        return new PlanSnapshotService() {
            @Override
            public SnapshotDetail loadLastPublication() {
                return new SnapshotDetail(null, affectations);
            }
        };
    }
}
