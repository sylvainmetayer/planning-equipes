package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.SolveurMcpTools.ViolationHardView;
import dev.sylvain.planning.service.ConstraintAnalysisStore;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;
import dev.sylvain.planning.service.PlanningService.PlanningDiagnostic;

/**
 * Covers the "connaître les erreurs exactes d'un run solveur avec des
 * contraintes hard" requirement of issue #107: only HARD constraints that
 * actually matched on the last analysis should be reported, with their
 * human-readable violation messages.
 */
class SolveurMcpToolsTest {

    @Test
    void neRemonteQueLesContraintesHardEffectivementViolees() {
        SolveurMcpTools tools = new SolveurMcpTools();
        tools.analysisStore = new ConstraintAnalysisStore();

        ConstraintDiagnostic hardViole = new ConstraintDiagnostic("posteDoitEtrePourvu", "-2hard/0medium/0soft", 2,
                List.of("poste P1 non pourvu", "poste P2 non pourvu"));
        ConstraintDiagnostic hardRespecte = new ConstraintDiagnostic("animateurDisponible", "0hard/0medium/0soft", 0,
                List.of());
        ConstraintDiagnostic mediumViole = new ConstraintDiagnostic("equilibrerCharge", "0hard/-5medium/0soft", 5,
                List.of());
        tools.analysisStore.record(new PlanningDiagnostic("-2hard/-5medium/0soft", 2,
                List.of(hardViole, hardRespecte, mediumViole), null, -2));

        List<ViolationHardView> violations = tools.expliquer_echec_contraintes_dures();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).contrainte()).isEqualTo("posteDoitEtrePourvu");
        assertThat(violations.get(0).nombreCorrespondances()).isEqualTo(2);
        assertThat(violations.get(0).violations()).containsExactly("poste P1 non pourvu", "poste P2 non pourvu");
    }

    @Test
    void renvoieUneListeVideSansAnalysePrealable() {
        SolveurMcpTools tools = new SolveurMcpTools();
        tools.analysisStore = new ConstraintAnalysisStore();

        assertThat(tools.expliquer_echec_contraintes_dures()).isEmpty();
    }
}
