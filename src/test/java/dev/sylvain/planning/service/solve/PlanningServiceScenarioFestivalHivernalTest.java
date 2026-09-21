package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full-scale regression test on the second <b>anonymised real-world</b>
 * fixture, {@code festival-hivernal.yaml}: the same event as
 * {@code festival-realiste-canicule.yaml} but described the way the organiser
 * actually enters it — a grid written in vacations, with the midday rotation
 * (12-13 / 13-14) and the evening reliefs carried by the stands' opening
 * windows.
 *
 * <h2>What it adds</h2>
 * <p>{@code festival-realiste-canicule} is sliced by the découpage; this one is not,
 * so it exercises the seats as the operator sees them and the rules that only
 * bite on a hand-written grid: the meal break of issue #438, the relay of the
 * on-post break ({@code pauseSurPosteSansRelais}), and the chain through the
 * week that {@code WeekRelocationMoveIteratorFactory} plays. Before that
 * move, this grid ended 1800 s at −33 hard, 26 of them on the Tuesday
 * montage; with it, zero hard in 67 s here.</p>
 *
 * <p><b>The measure issue #32 asks for before the mode could be retired.</b>
 * With the single hard rule — every break due owed a hole or a relay, no
 * checkbox, no medium rule to dose — this grid reaches <b>zero hard in 210 s</b>
 * from a cold start, well inside the 900 s ceiling below: construction
 * heuristic out at −117 hard after 55 s, local search to zero 155 s later,
 * ending 0hard/−7019medium/−17869soft (seed 0, this container; the
 * development machine reached 67 s under the old mode, so read the two
 * numbers as machines, not as rules). The grid needed no retailoring: the
 * relay places are already there, which is what the organisation said when it
 * settled the framework. Consigné dans {@code docs/contraintes.md}.</p>
 *
 * <p>The file pins no constraint weight, on purpose. Under the previous
 * design the relay was a separate rule that could be dosed, and dosing it to
 * 5 in a cold solve ended 900 s at −67 hard: the feasibility phase accepts
 * moves on the whole score, and a heavy rule pulls it away from the
 * hard-repairing chains. The rule is hard and undosable now, so that trap is
 * closed rather than documented.</p>
 *
 * <h2>Why it can be committed</h2>
 * <p>Names, mails, birth dates, places and game categories are fictitious;
 * dates are shifted by thirty weeks so weekdays hold; coordinates are
 * translated onto another town with the distances preserved. Hours,
 * headcounts, skills, opening windows and parameters are the original's.</p>
 *
 * <p>Tagged {@code scenario-lent}: run with {@code ./mvnw test -Pscenario-tests}.</p>
 */
@Tag("scenario-lent")
class PlanningServiceScenarioFestivalHivernalTest {

    /**
     * Safety ceiling, not a target: {@code solveUntilFeasible} returns as soon
     * as the hard score reaches zero. Measured at 67 s from a cold start on the
     * development machine (286 s for the same grid served by the application);
     * the CI runner is roughly half as fast.
     */
    private static final long SECONDS_LIMITE_SECURITE = 900L;

    @Test
    void festivalHivernalNeViolateAucuneContrainteHard() {
        PlanningService planningService = new PlanningService(
                420L,
                0L,
                ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
                new EmptyReferenceData(),
                new FeasibilityAnalyzer(),
                null,
                null,
                ConfigProvider.getConfig());

        // The file lists its seats: built as written, judged on the meal
        // windows, legal parameters and weights the file declares.
        PlanningEvenement problem = planningService.buildExample("festival-hivernal.yaml");
        assertThat(problem.getPostes()).hasSize(3438);
        assertThat(problem.getAnimateurs()).hasSize(153);

        PlanningEvenement solved = planningService.solveUntilFeasible(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
    }
}
