package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningFestival;

class PlanningServicePlainTest {

    @Test
    void solverAssignsAtLeastOneAnimatorOnSampleScenario() {
        ReferenceDataService referenceDataService = new ReferenceDataService();
        referenceDataService.init();

        PlanningService planningService = new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
        PlanningFestival problem = planningService.construireExempleSimple();

        PlanningFestival solved = planningService.resoudre(problem);

        assertThat(solved.getPostes())
                .anySatisfy(poste -> assertThat(poste.getAnimateur()).isNotNull());
    }
}