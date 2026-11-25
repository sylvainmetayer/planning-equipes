package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * A lecteur (ADR 0099) meets only the tools declaring {@code readOnlyHint =
 * true}. The refusal is {@link EditionCibleeInterceptor}'s, so what this test
 * holds is that <b>every tool crosses it</b> — or is {@link InstanceOnlyTool},
 * which a lecteur never passes either: a new write tool cannot reach a lecteur
 * by sitting in a class with neither binding.
 */
class McpLecteurStructurelleTest {

    @Test
    void everyWriteToolCrossesTheLecteurRefusalOrIsInstanceOnly() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            if (outil.getAnnotation(Tool.class).annotations().readOnlyHint()) {
                continue;
            }
            Class<?> classe = outil.getDeclaringClass();
            assertThat(classe.isAnnotationPresent(EditionCiblee.class)
                            || outil.isAnnotationPresent(InstanceOnlyTool.class)
                            || classe.isAnnotationPresent(InstanceOnlyTool.class))
                    .as(
                            "l'outil %s écrit : sa classe doit porter @EditionCiblee (qui refuse l'écriture à un"
                                    + " lecteur) ou lui-même @InstanceOnlyTool",
                            FeatureNames.of(outil))
                    .isTrue();
        }
    }
}
