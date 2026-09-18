package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import org.junit.jupiter.api.Test;

/**
 * The whole constraint set under {@code FULL_ASSERT} on a rung whose edition
 * declares the legal break taken on the post — the tenth, four days, eight
 * stands, twenty animateurs, {@code pauseSurPoste: true}.
 *
 * <p>That declaration is what makes this rung the right one for the weekly
 * caps of issue #31. They no longer sum a flat stream of seats: they group the
 * seats by day, join the legal parameters, deduct the breaks that day owes,
 * then group the days by ISO week. Two levels of {@code groupBy} with a
 * composed collector under them, and a map of week loads beside it for
 * {@code dureeHebdomadaireMaxDeuxSemaines} — the shapes where an incremental
 * fold goes wrong quietly. Timefold recomputes the score from scratch after
 * every move and refuses any drift, so a day retracted from one week and
 * re-inserted into another, or a break deducted on insert and not on retract,
 * fails here rather than in a plan nobody can explain.</p>
 *
 * <p>Three seconds is not a convergence budget and is not meant to be: what is
 * being exercised is every move the search tries in that time, each one
 * verified. The same run was played by hand on {@code festival-hivernal} — the
 * organiser's own grid, 153 animateurs, {@code pauseSurPoste: true} — without
 * corruption; it is not committed because a fixture that size under
 * FULL_ASSERT is minutes, not seconds.</p>
 */
class PlafondsHebdomadairesFullAssertTest {

    private static final String RUNG = "gamme-10-4j-8stands-20animateurs-journees-types-multiples";

    @Test
    void lesPlafondsHebdomadairesNeCorrompentPasLeScoreSousFullAssert() {
        Loaded loaded = load(RUNG);
        PlanningEvenement problem = loaded.problem();
        assertThat(problem.getParametresLegaux())
                .as("the rung must declare the break taken on the post, or this test exercises nothing")
                .anyMatch(parametres -> parametres.isPauseSurPoste());
        // Prepares the problem as a solve would: meal windows, quotas, weights.
        ScenarioLadder.service().diagnose(problem);

        SolverConfig config = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        config.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        config.setEnvironmentMode(EnvironmentMode.FULL_ASSERT);
        config.setTerminationConfig(new TerminationConfig().withSecondsSpentLimit(3L));

        PlanningEvenement solved =
                SolverFactory.<PlanningEvenement>create(config).buildSolver().solve(problem);

        assertThat(solved.getScore()).isNotNull();
    }
}
