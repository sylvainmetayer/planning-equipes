package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A lecteur (ADR 0099) meets only the tools declaring {@code readOnlyHint = true} and not {@link Computes}.
 * The refusal is {@link EditionCibleeInterceptor}'s, so what this test holds is that <b>every such tool
 * crosses it</b> — or is {@link InstanceOnlyTool}, which a lecteur never passes either — and that the tools
 * which compute are the ones carrying {@link Computes}, derived from their name.
 */
class McpLecteurStructurelleTest {

    /** A read-only tool so named computes on a hypothesis: its screen asks it by a {@code POST}. */
    private static final List<String> CALCUL_PREFIXES = List.of("simuler_", "previsualiser_", "suggerer_");

    /** Read-only tools that compute although their name does not say so, each with its reason. */
    private static final Set<String> CALCUL_NOMMES = Set.of(
            // Refreshes the analysis of the persisted plan: POST /api/constraints/diagnostic on screen.
            "diagnostiquer_plan",
            // Parses and checks a YAML the caller sends: POST /api/reference-data/valider-scenario-fichier.
            "valider_scenario_yaml",
            "diagnostiquer_scenario");

    @Test
    void everyWriteOrComputeToolCrossesTheLecteurRefusalOrIsInstanceOnly() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (outil.getAnnotation(Tool.class).annotations().readOnlyHint()
                    && !outil.isAnnotationPresent(Computes.class)) {
                continue;
            }
            Class<?> classe = outil.getDeclaringClass();
            assertThat(classe.isAnnotationPresent(EditionCiblee.class)
                            || outil.isAnnotationPresent(InstanceOnlyTool.class)
                            || classe.isAnnotationPresent(InstanceOnlyTool.class))
                    .as(
                            "l'outil %s écrit ou calcule : sa classe doit porter @EditionCiblee (qui le refuse à un"
                                    + " lecteur) ou lui-même @InstanceOnlyTool",
                            FeatureNames.of(outil))
                    .isTrue();
        }
    }

    @Test
    void theToolsThatComputeCarryComputes() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            String nom = FeatureNames.of(outil);
            boolean calcule = CALCUL_PREFIXES.stream().anyMatch(nom::startsWith) || CALCUL_NOMMES.contains(nom);
            assertThat(outil.isAnnotationPresent(Computes.class))
                    .as(
                            "%s : @Computes attendu %s (préfixes %s, noms %s de %s)",
                            nom,
                            calcule ? "présent" : "absent",
                            CALCUL_PREFIXES,
                            CALCUL_NOMMES,
                            getClass().getSimpleName())
                    .isEqualTo(calcule);
            if (calcule) {
                assertThat(outil.getAnnotation(Tool.class).annotations().readOnlyHint())
                        .as("%s : @Computes est pour un outil qui n'écrit rien", nom)
                        .isTrue();
            }
        }
    }
}
