package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.mcp.ContrainteMcpTools.ContrainteView;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintFloor;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
    void mergesTheDiagnosticOfTheLastAnalysis() {
        ConstraintDiagnostic diagnostic = new ConstraintDiagnostic(
                "posteDoitEtrePourvu", "-3hard/0medium/0soft", 3, List.of("poste P1 non pourvu"), null, null);

        ContrainteView view = ContrainteMcpTools.toView(DEFINITION, diagnostic, Set.of(), Map.of());

        assertThat(view.score()).isEqualTo("-3hard/0medium/0soft");
        assertThat(view.nombreCorrespondances()).isEqualTo(3);
        assertThat(view.ratioPlancher()).isNull();
        assertThat(view.motifPlancher()).isNull();
    }

    /** The floor travels over MCP as the ratio and the sentence, never as the route: an assistant has no screen. */
    @Test
    void exposesTheFloorRatioAndItsWording() {
        ConstraintDefinition souhaits = new ConstraintDefinition(
                "souhaitsIncompatibles", ConstraintCatalog.Niveau.MEDIUM, "Qualité d'organisation", "description");
        ConstraintDiagnostic diagnostic = new ConstraintDiagnostic(
                "souhaitsIncompatibles",
                "0hard/-12medium/0soft",
                12,
                List.of(),
                12,
                new ConstraintFloor(1.0, "SOUHAITS", "Aucun souhait déclaré.", "/animateurs"));

        ContrainteView view = ContrainteMcpTools.toView(souhaits, diagnostic, Set.of(), Map.of());

        assertThat(view.ratioPlancher()).isEqualTo(1.0);
        assertThat(view.motifPlancher()).isEqualTo("Aucun souhait déclaré.");
        // And the route stays behind: an assistant has no screen to send anyone
        // to, so the link of the floor must not travel with the rest.
        assertThat(view.toString()).doesNotContain("/animateurs");
    }

    @Test
    void remonteLePoidsEffectifDeLaContrainte() {
        ContrainteView regle = ContrainteMcpTools.toView(DEFINITION, null, Set.of(), Map.of("posteDoitEtrePourvu", 5));

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
