package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertCoreRules;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every limit at once: a hundred and twenty days, four hundred stands, a
 * thousand animateurs, some forty thousand seats. Not solved to zero hard —
 * what it measures is what the application holds: reading the file, building
 * the problem, the pre-solve analysis, and the construction heuristic up to
 * the last seat.
 *
 * <p>The construction heuristic evaluates every seat against every candidate,
 * so its cost grows with seats times animateurs; measured at 185 s for 6 480
 * seats and a thousand animateurs, it is the first wall this set runs into.
 * The solve stops the moment every seat is filled, so the duration read here
 * is the construction's own.</p>
 *
 * <p>Measured on the development machine, 3 GB heap: the file read in 1 s,
 * the analysis in 0.25 s, about 1 GB of heap in use, and the construction
 * 50 min 48 s for the 41 370 seats — ending at zero hard on its own, without
 * any local search. Its evaluation rate fell from about 17 000 to about
 * 10 900 moves a second along the way: the score gets dearer as the plan
 * fills.</p>
 *
 * <p>Tagged {@code scenario-extreme}, and the heaviest of them:
 * {@code ./mvnw test -Pscenario-extreme -DargLine=-Xmx3g
 * -Dtest=ScenarioExtremeCumulTest}, alone and with nothing else running — close
 * to an hour. A larger heap buys nothing here, and on a shared machine it is
 * what the memory pressure kills first.</p>
 */
@Tag("scenario-extreme")
class ScenarioExtremeCumulTest {

    private static final String NOM = "extreme-09-120j-400stands-1000animateurs-cumul";

    /**
     * Hard stop of the whole solve. The construction measured 50 min 48 s; ten
     * times that would be a working day, so the margin here is under two — the
     * ceiling says the construction got markedly slower, not that it moved.
     */
    private static final long BUDGET_SECONDS = 5400L;

    @Test
    void everyLimitAtOnceIsReadBuiltAnalysedAndConstructed() {
        Instant debut = Instant.now();
        Loaded loaded = load(NOM);
        Duration lecture = Duration.between(debut, Instant.now());

        Instant avantAnalyse = Instant.now();
        var faisabilite = loaded.feasibility();
        Duration analyse = Duration.between(avantAnalyse, Instant.now());

        Instant avantSolve = Instant.now();
        AtomicReference<Duration> construction = new AtomicReference<>();
        PlanningEvenement solved = ScenarioLadder.service()
                .solve(
                        loaded.problem(),
                        BUDGET_SECONDS,
                        solver -> solver.addEventListener(event -> {
                            if (construction.get() == null
                                    && event.getNewBestSolution().getPostes().stream()
                                            .noneMatch(poste -> poste.getAnimateur() == null)) {
                                construction.set(Duration.between(avantSolve, Instant.now()));
                                solver.terminateEarly();
                            }
                        }));
        Runtime runtime = Runtime.getRuntime();
        long memoireMo = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        System.out.printf(
                "CUMUL %s postes=%d lecture=%ds analyse=%dms construction=%s score=%s memoire=%dMo%n",
                NOM,
                loaded.problem().getPostes().size(),
                lecture.toSeconds(),
                analyse.toMillis(),
                construction.get(),
                solved.getScore(),
                memoireMo);

        assertThat(loaded.problem().getPostes()).hasSize(41_370);
        assertThat(faisabilite.feasible()).isTrue();
        assertThat(lecture).isLessThan(Duration.ofSeconds(60));
        assertThat(analyse).isLessThan(Duration.ofSeconds(60));
        assertThat(construction.get()).as("every seat filled within the budget").isNotNull();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
        if (solved.getScore().hardScore() == 0) {
            assertCoreRules(solved);
        }
    }
}
