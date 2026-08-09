package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
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
 *
 * <p>Tagged {@code scenario-lent} (~25s): excluded from the default
 * {@code ./mvnw test}/CI run (see the {@code scenario-tests} Maven profile in
 * {@code pom.xml}) and only run with {@code ./mvnw test -Pscenario-tests}.
 * Run it in the background (not a blocking foreground wait) when triggered
 * from an agent session — see AGENTS.md's "Costly test jobs" section.</p>
 */
@Tag("scenario-lent")
class PlanningServiceScenarioCompletTest {

    // Reaching hard-feasibility on scenario-complet.yaml is deterministic
    // (solverConfig.xml pins randomSeed=0), but the exact convergence time
    // varies with solver and dataset evolutions. Keep this guardrail aligned
    // with the production solve budget so this regression test remains stable
    // while still failing when hard-feasibility cannot be reached.
    private static final long SECONDS_LIMITE_SECURITE = 180L;

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
