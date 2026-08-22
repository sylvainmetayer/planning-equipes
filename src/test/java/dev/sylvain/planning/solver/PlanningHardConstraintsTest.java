package dev.sylvain.planning.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.PlanningService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class PlanningHardConstraintsTest {

    @Inject
    PlanningService planningService;

    @Test
    void generatedPlanningDoesNotViolateAnyHardConstraintOnNominalCase() {
        PlanningFestival problem = planningService.buildSimpleExample();

        PlanningFestival solved = planningService.solve(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isZero();
    }

    @Test
    void planningViolatesHardScoreWhenForcedAssignmentCannotBeSatisfied() {
        PlanningFestival problem = planningService.buildSimpleExample();

        ContrainteAdHoc contrainte = new ContrainteAdHoc("C1", TypeContrainteAdHoc.AFFECTATION_FORCEE);
        contrainte.setAnimateursConcernes(List.of(problem.getAnimateurs().get(0)));
        contrainte.setCreneau(
                new Creneau(Long.MAX_VALUE, 10, LocalDate.now().plusDays(20), LocalTime.of(9, 0), LocalTime.of(12, 0)));
        contrainte.setStand(problem.getPostes().get(0).getStand());
        contrainte.setCreeLe(Instant.now());
        problem.setContraintesAdHoc(List.of(contrainte));

        PlanningFestival solved = planningService.solve(problem);

        assertThat(solved.getScore()).isNotNull();
        assertThat(solved.getScore().hardScore()).isLessThan(0);
    }
}
