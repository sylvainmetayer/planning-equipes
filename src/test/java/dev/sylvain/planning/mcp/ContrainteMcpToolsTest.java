package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.mcp.ContrainteMcpTools.ContrainteView;
import dev.sylvain.planning.service.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;

class ContrainteMcpToolsTest {

    private static final ConstraintDefinition DEFINITION = new ConstraintDefinition(
            "posteDoitEtrePourvu", ConstraintCatalog.Niveau.HARD, "Affectation", "description");

    @Test
    void actifQuandAbsentDesContraintesDesactivees() {
        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, null, Set.of(), Map.of());

        assertThat(view.actif()).isTrue();
        assertThat(view.score()).isNull();
        assertThat(view.nombreCorrespondances()).isNull();
    }

    @Test
    void inactifQuandPresentDansLesContraintesDesactivees() {
        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, null, Set.of("posteDoitEtrePourvu"), Map.of());

        assertThat(view.actif()).isFalse();
    }

    @Test
    void fusionneLeDiagnosticDeLaDerniereAnalyse() {
        ConstraintDiagnostic diagnostic = new ConstraintDiagnostic("posteDoitEtrePourvu", "-3hard/0medium/0soft", 3,
                List.of("poste P1 non pourvu"));

        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, diagnostic, Set.of(), Map.of());

        assertThat(view.score()).isEqualTo("-3hard/0medium/0soft");
        assertThat(view.nombreCorrespondances()).isEqualTo(3);
    }

    @Test
    void remonteLePoidsEffectifDeLaContrainte() {
        ContrainteView regle = ContrainteMcpTools.toView(DEFINITION, null, Set.of(),
                Map.of("posteDoitEtrePourvu", 5));

        assertThat(regle.poids()).isEqualTo(5);
    }

    /**
     * A weight is per-edition and optional, so the map holds only the
     * constraints somebody has retuned. Reading 1 for the others is what makes
     * the listing complete rather than half-empty.
     */
    @Test
    void unPoidsAbsentVaut1() {
        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, null, Set.of(), Map.of("uneAutre", 4));

        assertThat(view.poids()).isEqualTo(1);
    }
}
