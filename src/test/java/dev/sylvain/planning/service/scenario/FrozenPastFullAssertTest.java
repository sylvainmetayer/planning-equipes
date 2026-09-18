package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import dev.sylvain.planning.service.solve.FrozenPast;
import dev.sylvain.planning.solver.PlanningConstraintProvider;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole constraint set under {@code FULL_ASSERT} on a real rung of the
 * ladder with past seats in it — the thirteenth: a civil week, premium and
 * exhausting stands, game categories, thirty animateurs. Timefold recomputes
 * the score from scratch after every move and refuses any incremental drift,
 * which is where a collector folded wrong around the {@code passe} flag would
 * show: the load balances with their count of seats ahead, the week loads
 * merged into a map, the sets of locations per day — every one of them is
 * retracted and re-inserted on each move the three seconds allow.
 *
 * <p>The past is seeded round-robin, competences or not: what was worked
 * yesterday is a fact the rules count and never reproach, so a past seat held
 * by somebody unfit for it is exactly the kind of history the fold has to
 * carry without charging it.</p>
 */
class FrozenPastFullAssertTest {

    private static final String RUNG = "gamme-13-7j-12stands-30animateurs-premium-epuisants";

    /** Wednesday 25 August 2027 at one in the afternoon: two days and a morning behind. */
    private static final PastHorizon MERCREDI_MIDI = new PastHorizon(LocalDate.of(2027, 8, 25), LocalTime.of(13, 0));

    @Test
    void theConstraintsHoldUnderFullAssertOnARungWithItsPastSeeded() {
        Loaded loaded = load(RUNG);
        PlanningEvenement problem = loaded.problem();
        int passes = FrozenPast.mark(problem.getPostes(), MERCREDI_MIDI);
        assertThat(passes).isPositive().isLessThan(problem.getPostes().size());
        Map<String, String> passeSeme = seedThePast(problem);
        FrozenPast.pin(problem.getPostes());
        problem.setPastHorizon(MERCREDI_MIDI);
        // Diagnosing prepares the problem as a solve would (meal windows,
        // quotas, weights), against the horizon it now carries.
        ScenarioLadder.service().diagnose(problem);

        SolverConfig config = SolverConfig.createFromXmlResource("solver/solverConfig.xml");
        config.setScoreDirectorFactoryConfig(
                new ScoreDirectorFactoryConfig().withConstraintProviderClass(PlanningConstraintProvider.class));
        config.setEnvironmentMode(EnvironmentMode.FULL_ASSERT);
        config.setTerminationConfig(new TerminationConfig().withSecondsSpentLimit(3L));

        PlanningEvenement solved =
                SolverFactory.<PlanningEvenement>create(config).buildSolver().solve(problem);

        // No score corruption thrown, and the past out exactly as it went in.
        assertThat(solved.getScore()).isNotNull();
        for (PosteAffectation poste : solved.getPostes()) {
            if (poste.isPasse()) {
                assertThat(poste.isVerrouille()).isTrue();
                String tenant = poste.getAnimateur() == null
                        ? null
                        : poste.getAnimateur().getId();
                assertThat(tenant).isEqualTo(passeSeme.get(poste.getId()));
            }
        }
    }

    /** Every past seat but one in three gets an animateur, in turn; the rest stay holes, pinned all the same. */
    private static Map<String, String> seedThePast(PlanningEvenement problem) {
        List<Animateur> animateurs = problem.getAnimateurs();
        Map<String, String> seme = new HashMap<>();
        int suivant = 0;
        int rang = 0;
        for (PosteAffectation poste : problem.getPostes()) {
            if (!poste.isPasse()) {
                continue;
            }
            if (rang++ % 3 != 2) {
                Animateur tenant = animateurs.get(suivant++ % animateurs.size());
                poste.setAnimateur(tenant);
                seme.put(poste.getId(), tenant.getId());
            } else {
                seme.put(poste.getId(), null);
            }
        }
        return seme;
    }
}
