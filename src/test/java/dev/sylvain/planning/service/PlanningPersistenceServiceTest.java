package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.PlanningFestival;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanningPersistenceServiceTest {

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Test
    void solvedPlanningIsPersistedToDatabase() {
        PlanningFestival problem = planningService.construireExempleSimple();
        PlanningFestival solved = planningService.resoudre(problem);

        int stored = persistenceService.persist(solved);

        assertThat(stored).isEqualTo(solved.getPostes().size());
        assertThat(persistenceService.countPersistedAssignments()).isEqualTo(stored);
        assertThat(stored).isPositive();
    }
}
