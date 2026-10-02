package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.Solver;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The job's hold on a two-stage solve (ADR 0067): one solver per stage handed
 * over in order, and a stop requested between the two ending the job there —
 * Timefold clears a stop requested before {@code solve()}, so without the
 * server's own check the second stage would run its whole share.
 */
class FeasibilityFirstStopTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 6);

    private final Stand stand = new Stand("S", "Stand", Set.of(), 1, 1, false);
    private final Creneau morning = new Creneau(1L, 1, MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
    private final Animateur x = new Animateur("X", "Xavier", "Un", LocalDate.of(1990, 1, 1), false);
    private final Animateur y = new Animateur("Y", "Yann", "Deux", LocalDate.of(1990, 1, 1), false);

    private PlanningService service(boolean published) {
        List<PlanSnapshotService.AffectationSnapshot> publication = published
                ? List.of(new PlanSnapshotService.AffectationSnapshot(
                        "P1", "S", "1", MONDAY.toString(), "09:00", "12:00", "X", null, null))
                : List.of();
        // Only the last publication is read by a solve: nothing else of the
        // service is reached, so none of its collaborators is needed.
        PlanSnapshotService snapshots = new PlanSnapshotService(0, null, null, null, null, null, null, null) {
            @Override
            public SnapshotDetail loadLastPublication() {
                return publication.isEmpty() ? null : new SnapshotDetail(null, publication);
            }
        };
        return new PlanningService(
                2L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                snapshots,
                ConfigProvider.getConfig());
    }

    private PlanningEvenement problem() {
        return new PlanningEvenement(MONDAY, List.of(x, y), List.of(new PosteAffectation("P1", stand, morning)));
    }

    @Test
    void aStopBetweenTheStagesEndsTheJobAfterTheFirst() {
        List<Solver<PlanningEvenement>> handed = new ArrayList<>();
        Consumer<Solver<PlanningEvenement>> cancelOnSecond = solver -> {
            handed.add(solver);
            if (handed.size() == 2) {
                // What SolverJob.attachSolver does for a cancel that arrived meanwhile.
                solver.terminateEarly();
            }
        };

        SolveRunner.Solved solved = service(true).solveReporting(problem(), SolveBudget.ofSeconds(2L), cancelOnSecond);

        assertThat(handed).hasSize(2);
        assertThat(solved.feasibilityFirst()).isNotNull();
        assertThat(solved.feasibilityFirst().stoppedAfterFeasibility())
                .as("the second stage never ran")
                .isTrue();
        assertThat(solved.feasibilityFirst().polishingSeconds()).isZero();
        assertThat(solved.planning().getPostes().getFirst().getAnimateur())
                .as("the first stage's plan is what the job returns")
                .isNotNull();
    }

    @Test
    void anEditionNeverPublishedSolvesInOneStage() {
        List<Solver<PlanningEvenement>> handed = new ArrayList<>();

        SolveRunner.Solved solved = service(false).solveReporting(problem(), SolveBudget.ofSeconds(1L), handed::add);

        assertThat(handed).hasSize(1);
        assertThat(solved.feasibilityFirst()).isNull();
    }
}
