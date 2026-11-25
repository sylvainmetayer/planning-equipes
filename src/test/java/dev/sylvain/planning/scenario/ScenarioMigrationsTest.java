package dev.sylvain.planning.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioMigrationsTest {

    private static Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("festival", Map.of("dateDebut", "2026-07-08"));
        return document;
    }

    @Test
    void theChainEndsAtTheVersionTheWriterProduces() {
        List<ScenarioMigrations.Link> chain = ScenarioMigrations.CHAIN;
        for (int i = 0; i < chain.size(); i++) {
            assertThat(chain.get(i).from()).isEqualTo(i + 1);
        }
        assertThat(chain.size() + 1).isEqualTo(ScenarioBinder.SCHEMA_VERSION);
    }

    @Test
    void v1ToV2LosesNothingAndWarnsOfNothing() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Object> converted = ScenarioMigrations.v1ToV2(document(), warnings);

        assertThat(converted).isEqualTo(document());
        assertThat(warnings).isEmpty();
    }

    @Test
    void migrateStampsTheCurrentVersionAndLeavesTheInputUntouched() {
        Map<String, Object> input = document();

        ScenarioMigrations.Migrated migrated = ScenarioMigrations.migrate(input, 1);

        assertThat(migrated.document()).containsEntry("schemaVersion", ScenarioBinder.SCHEMA_VERSION);
        assertThat(migrated.document()).containsKey("festival");
        assertThat(input).doesNotContainKey("schemaVersion");
    }

    @Test
    void aDocumentAlreadyCurrentSkipsEveryLink() {
        assertThat(ScenarioMigrations.migrate(document(), ScenarioBinder.SCHEMA_VERSION)
                        .warnings())
                .isEmpty();
    }
}
