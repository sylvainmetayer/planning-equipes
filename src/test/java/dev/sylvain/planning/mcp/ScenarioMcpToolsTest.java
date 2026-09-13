package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sylvain.planning.service.BusinessError;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * What {@code importer_scenario_yaml} does with a file it cannot read.
 *
 * <p>Until A3 of issue #392 this tool went through {@code
 * ReferenceDataResource} and decided whether the import had failed by reading
 * {@code Response.getStatus() >= 400}. That branch could never run: the status
 * is produced by a JAX-RS {@code ExceptionMapper}, at the HTTP boundary, and
 * the tool called the resource in process. The failure it was written for
 * never reached it in that shape.</p>
 *
 * <p>Nothing pinned that, which is why the dead branch survived a rewrite. It
 * is pinned now: an unreadable scenario surfaces as a business error, on the
 * MCP side as on the REST side.</p>
 */
@QuarkusTest
class ScenarioMcpToolsTest {

    @Inject
    ScenarioMcpTools scenarioTools;

    @Test
    void unYamlIllisibleRemonteUneErreurMetier() {
        assertThatThrownBy(() -> scenarioTools.importer_scenario_yaml("festival: [pas fermé", null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class)
                .hasMessageContaining("YAML invalide");
    }

    @Test
    void unFichierVideRemonteUneErreurMetier() {
        assertThatThrownBy(() -> scenarioTools.importer_scenario_yaml("", null))
                .isInstanceOf(ToolCallException.class)
                .hasCauseInstanceOf(BusinessError.Invalid.class);
    }

    /**
     * The validator says what is wrong without importing anything — and says it
     * for a file the importer would also refuse, since both now walk in by the
     * same door.
     */
    @Test
    void validerUnYamlIllisibleRendLesErreursSansRienImporter() {
        ScenarioMcpTools.ValidationResult resultat = scenarioTools.valider_scenario_yaml("festival: [pas fermé");

        assertThat(resultat.valide()).isFalse();
        assertThat(resultat.erreurs()).isNotEmpty();
    }

    /**
     * Names, not paths: the tool's whole contract is that what it returns can
     * be handed straight back to {@code importer_scenario}. The assertion is on
     * the shape rather than on a list of files, which grows every time a
     * scenario is added to the folder.
     */
    @Test
    void listerLesScenariosRendDesNomsReimportables() {
        assertThat(scenarioTools.lister_scenarios())
                .isNotEmpty()
                .allSatisfy(nom -> assertThat(nom).matches(".+\\.ya?ml").doesNotContain("/"));
    }
}
