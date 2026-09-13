package dev.sylvain.planning.service.scenario;

import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertCoreRules;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.assertFeasible;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.brokenHardConstraints;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.load;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.matchCounts;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.seatsByAnimateur;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveFor;
import static dev.sylvain.planning.service.scenario.ScenarioLadder.solveUntilFeasible;
import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.AnomalyType;
import dev.sylvain.planning.service.scenario.ScenarioLadder.Loaded;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * The shapes nobody should enter but somebody will: no animateur, no timeslot,
 * stands that never open, one seat for a thousand people, a team of minors
 * only. Each is accepted, and each pins what the application does with it —
 * a message, a cause, an empty plan — rather than an exception. Small files,
 * fast solves: they stay in the default run.
 */
class ScenarioExtremeDegenerateTest {

    @Test
    void anEditionWithoutAnyAnimateurSaysSoAndLeavesEverySeatEmpty() {
        Loaded loaded = load("extreme-10-sans-animateur");
        assertThat(loaded.problem().getPostes()).hasSize(6);
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().message()).startsWith("Aucun animateur n'est saisi");

        PlanningEvenement solved = solveFor(loaded, 3L);

        assertThat(solved.getPostes()).allMatch(poste -> poste.getAnimateur() == null);
        assertThat(brokenHardConstraints(solved)).containsExactly("posteDoitEtrePourvu");
        assertThat(matchCounts(solved)).containsEntry("posteDoitEtrePourvu", 6);
    }

    @Test
    void oneSeatForAThousandAnimateurs() {
        Loaded loaded = load("extreme-11-un-siege-pour-1000-animateurs");
        assertThat(loaded.problem().getPostes()).hasSize(1);
        assertThat(loaded.problem().getAnimateurs()).hasSize(1000);

        PlanningEvenement solved = solveUntilFeasible(loaded, 60L);

        assertFeasible(solved);
        assertThat(seatsByAnimateur(solved)).hasSize(1);
    }

    /**
     * No adult at all: a minor is never alone on a stand, so every seat stays
     * empty — a minor alone costs more than an empty seat. The pre-solve
     * analysis sees it too: a minor counts only beside an adult.
     */
    @Test
    void aTeamOfMinorsOnlyLeavesEverySeatEmptyRatherThanAMinorAlone() {
        Loaded loaded = load("extreme-12-uniquement-des-mineurs");
        assertThat(loaded.problem().getPostes()).hasSize(24);
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().causes())
                .allSatisfy(cause -> assertThat(cause.capacite()).isZero());

        PlanningEvenement solved = solveFor(loaded, 5L);

        assertThat(brokenHardConstraints(solved)).containsExactly("posteDoitEtrePourvu");
        assertThat(solved.getPostes()).allMatch(poste -> poste.getAnimateur() == null);
        assertCoreRules(solved);
    }

    @Test
    void standsThatNeverOpenMakeAProblemWithoutSeats() {
        Loaded loaded = load("extreme-14-stands-jamais-ouverts");
        assertThat(loaded.problem().getPostes()).isEmpty();
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().message())
                .isEqualTo(dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.MESSAGE_SANS_POSTE);
        assertThat(OuvertureStandsAnalyzer.analyze(new ArrayList<>(loaded.stands()), new ArrayList<>(loaded.creneaux()))
                        .anomalies())
                .filteredOn(anomalie -> anomalie.type() == AnomalyType.STAND_JAMAIS_OUVERT)
                .extracting(OuvertureStandsAnalyzer.Anomaly::standId)
                .containsExactlyInAnyOrder("STAND-1", "STAND-2", "STAND-3");

        PlanningEvenement solved = solveUntilFeasible(loaded, 30L);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).isEmpty();
    }

    @Test
    void anEditionWithoutAnyTimeslotReadsAnalysesAndSolves() {
        Loaded loaded = load("extreme-15-sans-creneau");
        assertThat(loaded.creneaux()).isEmpty();
        assertThat(loaded.problem().getPostes()).isEmpty();
        assertThat(loaded.feasibility().causes()).isEmpty();
        assertThat(loaded.feasibility().feasible()).isFalse();
        assertThat(loaded.feasibility().message())
                .isEqualTo(dev.sylvain.planning.service.analyse.FeasibilityAnalyzer.MESSAGE_SANS_CRENEAU);

        PlanningEvenement solved = solveUntilFeasible(loaded, 30L);

        assertThat(solved.getScore().hardScore()).isZero();
    }
}
