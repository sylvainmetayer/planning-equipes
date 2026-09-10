package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.solve.PlanningService;

/**
 * A scenario carrying more than fifty YAML aliases is read.
 *
 * <p>SnakeYAML caps aliases at fifty by default, as a billion-laughs guard.
 * Our own exporter used to emit one anchor and an alias per animateur sharing
 * the same list — a real edition of 153 animateurs produced 152 of them — so
 * the application refused to re-import files it had itself produced, with a
 * message about "aliases for non-scalar nodes" that means nothing to the
 * operator reading it. Eleven such files were still sitting on a disk when
 * this was found.</p>
 *
 * <p>The fixture is built the way those files were: one list instance shared
 * by every animateur, dumped by SnakeYAML, which anchors it once and aliases
 * it thereafter. Reproducing the cause rather than pasting a symptom is what
 * makes this test keep meaning something.</p>
 */
@QuarkusTest
class ScenarioAliasYamlTest {

    private static final int ANIMATEURS = 60;

    @Inject
    PlanningService planningService;

    @Test
    void unScenarioAvecPlusDeCinquanteAliasEstLu() throws Exception {
        String yamlAvecAlias = scenarioSharingOneListInstance();

        long alias = yamlAvecAlias.lines().filter(line -> line.matches(".*\\*id\\d+.*")).count();
        assertThat(alias)
                .as("le dump doit bien porter des alias, sinon le test ne prouve rien")
                .isGreaterThan(50);

        ScenarioYamlReader.ScenarioImporte importe = planningService.buildFromScenarioText(yamlAvecAlias);

        assertThat(importe.planning().getAnimateurs()).hasSize(ANIMATEURS);
    }

    /**
     * @return {@code scenario.yml} with its animateurs multiplied until they
     *         pass the alias cap, every one of them pointing at the
     *         <b>same</b> {@code joursIndisponibles} instance — which is what
     *         makes SnakeYAML anchor it once and alias it afterwards.
     */
    @SuppressWarnings("unchecked")
    private static String scenarioSharingOneListInstance() throws Exception {
        Map<String, Object> data = new Yaml()
                .load(Files.readString(Path.of("src/main/resources/scenarios/scenario.yml")));

        List<Map<String, Object>> animateurs = (List<Map<String, Object>>) data.get("animateurs");
        Map<String, Object> modele = animateurs.get(0);
        List<Object> joursPartages = new ArrayList<>();

        List<Map<String, Object>> gonfles = new ArrayList<>();
        for (int i = 0; i < ANIMATEURS; i++) {
            Map<String, Object> copie = new LinkedHashMap<>(modele);
            copie.put("id", "ALIAS" + i);
            copie.put("email", "alias" + i + "@example.test");
            copie.put("joursIndisponibles", joursPartages);
            gonfles.add(copie);
        }
        data.put("animateurs", gonfles);

        return new Yaml().dump(data);
    }
}
