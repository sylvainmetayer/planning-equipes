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
    void v1ToV2LeavesADocumentWithoutTypologiesAlone() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Object> converted = ScenarioMigrations.v1ToV2(document(), warnings);

        assertThat(converted).isEqualTo(document());
        assertThat(warnings).isEmpty();
    }

    @Test
    void v1ToV2RenamesTheNinjaFlagOfATypologyAndKeepsTheRest() {
        Map<String, Object> document = document();
        document.put(
                "typologies",
                List.of(
                        new LinkedHashMap<>(Map.of("id", "JOKER", "ninja", true)),
                        new LinkedHashMap<>(Map.of("id", "JEUX", "label", "Jeux"))));
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> converted = ScenarioMigrations.v1ToV2(document, warnings);

        assertThat(converted.get("typologies"))
                .isEqualTo(List.of(Map.of("id", "JOKER", "polyvalent", true), Map.of("id", "JEUX", "label", "Jeux")));
        assertThat(warnings).isEmpty();
    }

    @Test
    void v2ToV3MovesTheParameterSectionsUnderParametresAndDropsTheBudgetSayingSo() {
        Map<String, Object> document = document();
        document.put("parametresLegaux", Map.of("dureePauseMinutes", 30));
        document.put("parametresQualite", Map.of("joursConsecutifsMax", 5));
        document.put("parametresSolveur", Map.of("dureeResolutionSecondes", 400));
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> converted = ScenarioParametresLink.convert(document, warnings);

        assertThat(converted)
                .doesNotContainKeys("parametresLegaux", "parametresQualite", "parametresSolveur")
                .containsEntry(
                        "parametres",
                        Map.of(
                                "legal", Map.of("dureePauseMinutes", 30),
                                "organisation", Map.of("joursConsecutifsMax", 5)));
        // The budget is the instance's now (ADR 0110): never lost in silence.
        assertThat(warnings)
                .singleElement()
                .asString()
                .contains("parametresSolveur")
                .contains("SOLVER_SECONDS_LIMIT");
    }

    @Test
    void v2ToV3KeepsASectionTheFileAlreadyCarriesAndSaysWhatItDropped() {
        Map<String, Object> document = document();
        document.put("parametres", Map.of("legal", Map.of("dureePauseMinutes", 20)));
        document.put("parametresLegaux", Map.of("dureePauseMinutes", 30));
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> converted = ScenarioParametresLink.convert(document, warnings);

        assertThat(converted)
                .doesNotContainKey("parametresLegaux")
                .containsEntry("parametres", Map.of("legal", Map.of("dureePauseMinutes", 20)));
        assertThat(warnings).singleElement().asString().contains("parametresLegaux", "parametres.legal");
    }

    @Test
    void v2ToV3LeavesADocumentWithoutParameterSectionsAlone() {
        assertThat(ScenarioParametresLink.convert(document(), new java.util.ArrayList<>()))
                .isEqualTo(document());
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
