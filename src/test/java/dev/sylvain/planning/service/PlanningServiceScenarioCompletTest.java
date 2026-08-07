package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.PlanningFestival;

/**
 * Full-scale regression test: {@code scenario-complet.yaml} (2088 postes, 150
 * animateurs) must always solve to zero hard-constraint violations — every
 * poste filled, nobody double-booked, no legal (minor) violation. Built the
 * plain (non-Quarkus) way, like {@link PlanningServicePlainTest}, so it needs
 * no database and can give the solver the time it actually takes to converge
 * on a scenario this size, unconstrained by the %test profile's 3s/2s solver
 * budget (tuned for the small nominal scenario, far too short here).
 */
class PlanningServiceScenarioCompletTest {

    // Reaching hard-feasibility on scenario-complet.yaml is deterministic
    // (solverConfig.xml pins randomSeed=0) and takes ~25s on this codebase's
    // reference hardware. 120s leaves a generous safety margin so a moderate
    // regression in convergence speed fails on wall-clock symptoms rather
    // than silently, without letting the suite hang for a full production
    // time budget (180s) that's mostly spent on soft/medium polishing this
    // test doesn't care about.
    private static final long SECONDS_LIMITE_SECURITE = 120L;

    @Test
    void scenarioCompletNeViolateAucuneContrainteHard() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();
        PlanningService planningService = new PlanningService(420L, 0L, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());

        PlanningFestival problem = planningService.construireExemple();
        PlanningFestival solved = planningService.resoudreJusquaFaisabilite(problem, SECONDS_LIMITE_SECURITE);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPostes()).noneMatch(poste -> poste.getAnimateur() == null);
    }
}
