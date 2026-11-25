package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.ToolArg;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
 * edition: the scenario files shipped on disk, a pure YAML validation, the
 * solver job registry — global, each job carrying the edition it was launched
 * for — and the KPI history, which is deliberately unscoped so two editions
 * can be compared side by side. Giving them an {@code edition} argument would
 * be a lie, since nothing in their answer would change.</p>
 */
class McpEditionStructurelleTest {

    private static final Set<String> HORS_EDITION = Set.of(
            "lister_scenarios",
            "valider_scenario_yaml",
            "arreter_solveur",
            "statut_solveur",
            "lister_jobs",
            "supprimer_job",
            "lister_kpi_historique",
            "supprimer_kpi_historique",
            "etat_sauvegardes",
            "modifier_sauvegardes");

    /**
     * The tools that designate no edition yet stay open to an organiser reaching
     * {@code /mcp} by OAuth (ADR 0097), each with what keeps it inside the
     * organiser's editions. Every other tool without an edition argument is
     * {@link InstanceOnlyTool}: a new one cannot reach every edition by omission.
     */
    private static final Map<String, String> SANS_EDITION_OUVERTS_A_L_ORGANISATEUR = Map.of(
            "lister_scenarios", "les fichiers livrés avec l'application, aucune donnée d'édition",
            "valider_scenario_yaml", "valide le texte reçu, ne lit rien",
            "arreter_solveur", "SolveurMcpTools ne voit que les jobs des éditions visibles",
            "statut_solveur", "SolveurMcpTools ne voit que les jobs des éditions visibles",
            "lister_jobs", "SolveurMcpTools ne voit que les jobs des éditions visibles",
            "supprimer_job", "SolveurMcpTools ne voit que les jobs des éditions visibles",
            "lister_kpi_historique", "KpiHistoriqueService filtre par visibleToCaller",
            "supprimer_kpi_historique", "KpiHistoriqueService filtre par visibleToCaller",
            "lister_editions", "filtrée par visibleToCaller",
            "edition_courante", "refusée quand l'édition active n'est pas visible");

    @Test
    void aToolNamingNoEditionIsInstanceOnlyOrArgued() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (argumentEdition(outil) != null
                    || SANS_EDITION_OUVERTS_A_L_ORGANISATEUR.containsKey(FeatureNames.of(outil))) {
                continue;
            }
            assertThat(outil.isAnnotationPresent(InstanceOnlyTool.class)
                            || outil.getDeclaringClass().isAnnotationPresent(InstanceOnlyTool.class))
                    .as(
                            "l'outil %s ne désigne pas d'édition : @InstanceOnlyTool, ou une raison dans"
                                    + " SANS_EDITION_OUVERTS_A_L_ORGANISATEUR (ADR 0097)",
                            FeatureNames.of(outil))
                    .isTrue();
        }
    }

    /** The edition tools themselves designate their target explicitly, argument by argument. */
    private static final String OUTILS_DEDITION = EditionMcpTools.class.getName();

    @Test
    void everyToolWorkingInAnEditionLetsItBeDesignated() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (HORS_EDITION.contains(FeatureNames.of(outil))
                    || outil.getDeclaringClass().getName().equals(OUTILS_DEDITION)) {
                continue;
            }
            assertThat(argumentEdition(outil))
                    .as("l'outil %s doit porter un argument @EditionArg (issue #181)", FeatureNames.of(outil))
                    .isNotNull();
        }
    }

    @Test
    void theEditionArgumentIsRequiredAndDescribedTheSameEverywhere() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            Parameter edition = argumentEdition(outil);
            if (edition == null) {
                continue;
            }
            ToolArg description = edition.getAnnotation(ToolArg.class);
            assertThat(description)
                    .as("l'argument edition de %s doit être déclaré comme argument d'outil", FeatureNames.of(outil))
                    .isNotNull();
            assertThat(description.required())
                    .as(
                            "l'argument edition de %s est obligatoire : un appel sans lui est refusé (ADR 0072),"
                                    + " et le schéma publié doit le dire",
                            FeatureNames.of(outil))
                    .isTrue();
            assertThat(description.description())
                    .as("description de l'argument edition de %s", FeatureNames.of(outil))
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
                    .as(
                            "%s porte des outils avec un argument edition : sans @EditionCiblee sur la classe,"
                                    + " l'argument serait accepté puis ignoré",
                            outil.getDeclaringClass().getSimpleName())
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
