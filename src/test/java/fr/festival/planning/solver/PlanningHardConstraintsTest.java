package fr.festival.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fr.festival.planning.domain.PlanningFestival;
import fr.festival.planning.service.PlanningService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class PlanningHardConstraintsTest {

    @Inject
    PlanningService planningService;

    @Test
    void planningGenereNeVioleAucuneContrainteDureSurLeCasNominal() {
        PlanningFestival problem = planningService.construireExemple();

        PlanningFestival solved = planningService.resoudre(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
    }
}
