package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import dev.sylvain.planning.service.solve.FrozenPast;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * <p>The seats already worked are pinned, so what the rules see is a real
 * mid-event problem rather than a blank one. That pairing is the point:
 * neither FULL_ASSERT test covered it — {@code gamme-13} carries a past but
 * declares no break on the post, this rung declared the break but had no past
 * — and the two rules read the past at two different grains. The weekly caps
 * fold « is any day of this week still ahead » into their collector;
 * {@code pauseSurPosteSansRelais} asks it of the single seat a relay would
 * have had to cover. A fold that answers one grain with the other stays
 * invisible until a mid-event re-solve stops reaching zero.</p>
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

    /** Friday 3 September 2027 at two in the afternoon: two days and a morning of the rung are behind us. */
    private static final PastHorizon VENDREDI_APRES_MIDI =
            new PastHorizon(LocalDate.of(2027, 9, 3), LocalTime.of(14, 0));

    @Test
    void lesPlafondsHebdomadairesNeCorrompentPasLeScoreSousFullAssert() {
        Loaded loaded = load(RUNG);
        PlanningEvenement problem = loaded.problem();
        assertThat(problem.getParametresLegaux())
                .as("the rung must carry its legal parameters, or the weekly caps read nothing")
                .isNotEmpty();
        int passes = FrozenPast.mark(problem.getPostes(), VENDREDI_APRES_MIDI);
        assertThat(passes)
                .as("the horizon must cut the rung in two, or the past is not exercised")
                .isPositive()
                .isLessThan(problem.getPostes().size());
        FrozenPast.pin(problem.getPostes());
        problem.setPastHorizon(VENDREDI_APRES_MIDI);
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
