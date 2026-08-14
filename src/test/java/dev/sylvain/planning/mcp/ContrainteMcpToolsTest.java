package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.ContrainteMcpTools.ContrainteView;
import dev.sylvain.planning.service.PlanningService.ConstraintDiagnostic;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;

class ContrainteMcpToolsTest {

    private static final ConstraintDefinition DEFINITION = new ConstraintDefinition(
            "posteDoitEtrePourvu", ConstraintCatalog.Niveau.HARD, "Affectation", "description");

    @Test
    void actifQuandAbsentDesContraintesDesactivees() {
        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, null, Set.of());

        assertThat(view.actif()).isTrue();
        assertThat(view.score()).isNull();
        assertThat(view.nombreCorrespondances()).isNull();
    }

    @Test
    void inactifQuandPresentDansLesContraintesDesactivees() {
        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, null, Set.of("posteDoitEtrePourvu"));

        assertThat(view.actif()).isFalse();
    }

    @Test
    void fusionneLeDiagnosticDeLaDerniereAnalyse() {
        ConstraintDiagnostic diagnostic = new ConstraintDiagnostic("posteDoitEtrePourvu", "-3hard/0medium/0soft", 3,
                List.of("poste P1 non pourvu"));

        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, diagnostic, Set.of());

        assertThat(view.score()).isEqualTo("-3hard/0medium/0soft");
        assertThat(view.nombreCorrespondances()).isEqualTo(3);
    }
}
