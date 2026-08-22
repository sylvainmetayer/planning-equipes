package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolArg;

/**
 * Structural guard for issue #181: a tool that works inside an edition must
 * let the caller name which one.
 *
 * <p>The defect this closes is silence, not absence. An MCP call is not a
 * JAX-RS request, so a tool without an {@code edition} argument resolves to
 * the default edition and says nothing about it — a reading looks like the
 * whole truth, and a write can land in the wrong edition unnoticed. Asserting
 * it here rather than tool by tool means a tool added later cannot reopen the
 * hole by simply not thinking about it.</p>
 *
 * <p>The exemptions are the tools that read and write nothing inside an
 * edition: the scenario files shipped on disk, a pure YAML validation, and the
 * solver job registry — global, each job carrying the edition it was launched
 * for. Giving them an {@code edition} argument would be a lie, since nothing
 * in their answer would change.</p>
 */
class McpEditionStructurelleTest {

    private static final Set<String> HORS_EDITION = Set.of(
            "lister_scenarios", "valider_scenario_yaml",
            "arreter_solveur", "statut_solveur", "lister_jobs", "supprimer_job");

    /** The edition tools themselves designate their target explicitly, argument by argument. */
    private static final String OUTILS_DEDITION = EditionMcpTools.class.getName();

    @Test
    void chaqueOutilQuiTravailleDansUneEditionLaisseLaDesigner() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (HORS_EDITION.contains(outil.getName())
                    || outil.getDeclaringClass().getName().equals(OUTILS_DEDITION)) {
                continue;
            }
            assertThat(argumentEdition(outil))
                    .as("l'outil %s doit porter un argument @EditionArg (issue #181)", outil.getName())
                    .isNotNull();
        }
    }

    @Test
    void largumentEditionEstFacultatifEtDecritDeLaMemeFaconPartout() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            Parameter edition = argumentEdition(outil);
            if (edition == null) {
                continue;
            }
            ToolArg description = edition.getAnnotation(ToolArg.class);
            assertThat(description)
                    .as("l'argument edition de %s doit être déclaré comme argument d'outil", outil.getName())
                    .isNotNull();
            assertThat(description.required())
                    .as("l'argument edition de %s doit rester facultatif : sans lui, l'édition courante"
                            + " (comportement d'avant #181)", outil.getName())
                    .isFalse();
            assertThat(description.description())
                    .as("description de l'argument edition de %s", outil.getName())
                    .isEqualTo(EditionArg.DESCRIPTION);
        }
    }

    @Test
    void chaqueClasseDOutilsEstBrancheeSurLinterceptorDEdition() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (argumentEdition(outil) == null) {
                continue;
            }
            assertThat(outil.getDeclaringClass().isAnnotationPresent(EditionCiblee.class))
                    .as("%s porte des outils avec un argument edition : sans @EditionCiblee sur la classe,"
                            + " l'argument serait accepté puis ignoré", outil.getDeclaringClass().getSimpleName())
                    .isTrue();
        }
    }

    private static Parameter argumentEdition(Method outil) {
        return Arrays.stream(outil.getParameters())
                .filter(parametre -> parametre.isAnnotationPresent(EditionArg.class))
                .findFirst()
                .orElse(null);
    }
}
