package dev.sylvain.planning.service.solve;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.EmptyReferenceData;
import dev.sylvain.planning.service.referentiel.ReferenceData;

class PlanningServicePlainTest {

    @Test
    void solverAssignsAtLeastOneAnimatorOnSampleScenario() {
        ReferenceData referenceDataService = new EmptyReferenceData();

        PlanningService planningService = new PlanningService(3L, 2L, ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT, referenceDataService, new FeasibilityAnalyzer(),
                ConfigProvider.getConfig());
        PlanningEvenement problem = planningService.buildSimpleExample();

        PlanningEvenement solved = planningService.solve(problem);

        assertThat(solved.getPostes())
                .anySatisfy(poste -> assertThat(poste.getAnimateur()).isNotNull());
    }
}