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
    void v3ToV4ChangesNothingAndAFileWithoutLevelsReadsAsTheCatalogue() {
        Map<String, Object> document = document();
        document.put("contraintes", Map.of("poids", Map.of("equilibrerCharge", 25)));

        assertThat(ScenarioMigrations.v3ToV4(document, new java.util.ArrayList<>()))
                .isEqualTo(document);
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

    private static Map<String, Object> withRules(Map<String, Object> contraintes) {
        Map<String, Object> document = document();
        document.put("contraintes", contraintes);
        return document;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rulesOf(Map<String, Object> document) {
        return (Map<String, Object>) document.get("contraintes");
    }

    @Test
    void anOldRuleNameIsReadAsTheRuleThatMergedItAndSaidSo() {
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> converted = ScenarioMigrations.renameRetiredRules(
                withRules(Map.of(
                        "desactivees",
                                List.of(
                                        "dureeQuotidienneMaxMajeur",
                                        "dureeQuotidienneMaxMineur",
                                        "reposQuotidienMinimal"),
                        "poids", Map.of("travailContinuMaxMineur", 3))),
                warnings);

        assertThat(rulesOf(converted).get("desactivees"))
                .isEqualTo(List.of("reposQuotidienMinimal", "dureeQuotidienneMax"));
        assertThat(rulesOf(converted).get("poids")).isEqualTo(Map.of("travailContinuMax", 3));
        assertThat(warnings)
                .hasSize(3)
                .anySatisfy(warning -> assertThat(warning)
                        .contains("dureeQuotidienneMaxMajeur")
                        .contains("dureeQuotidienneMax"));
    }

    @Test
    void twoOldRulesFoldedOnTheMostProtectiveReading() {
        Map<String, Object> converted = ScenarioMigrations.renameRetiredRules(
                withRules(Map.of(
                        // only one of the pair off: the folded rule stays on
                        "desactivees", List.of("travailContinuMaxMajeur"),
                        "poids", Map.of("dureeQuotidienneMaxMajeur", 3, "dureeQuotidienneMaxMineur", 25),
                        "niveaux",
                                Map.of(
                                        "dureeQuotidienneMaxMajeur",
                                        "PREFERENCE",
                                        "dureeQuotidienneMaxMineur",
                                        "OBLIGATOIRE"))),
                new java.util.ArrayList<>());

        assertThat(rulesOf(converted).get("desactivees")).isEqualTo(List.of());
        assertThat(rulesOf(converted).get("poids")).isEqualTo(Map.of("dureeQuotidienneMax", 25));
        assertThat(rulesOf(converted).get("niveaux")).isEqualTo(Map.of("dureeQuotidienneMax", "OBLIGATOIRE"));
    }

    @Test
    void aBareWeightKeyDoesNotBreakTheFold() {
        java.util.HashMap<String, Object> poids = new java.util.HashMap<>();
        poids.put("dureeQuotidienneMaxMajeur", null);
        poids.put("dureeQuotidienneMaxMineur", 25);
        poids.put("equilibrerCharge", null);

        Map<String, Object> converted =
                ScenarioMigrations.renameRetiredRules(withRules(Map.of("poids", poids)), new java.util.ArrayList<>());

        Map<String, Object> expected = new java.util.HashMap<>();
        expected.put("dureeQuotidienneMax", 25);
        expected.put("equilibrerCharge", null);
        assertThat(rulesOf(converted).get("poids")).isEqualTo(expected);
    }

    @Test
    void aDocumentWithoutOldNamesIsLeftAloneAndSilent() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Object> document = withRules(Map.of("desactivees", List.of("dureeQuotidienneMax")));

        assertThat(ScenarioMigrations.renameRetiredRules(document, warnings)).isSameAs(document);
        assertThat(ScenarioMigrations.renameRetiredRules(document(), warnings)).isEqualTo(document());
        assertThat(warnings).isEmpty();
    }

    @Test
    void theWholeChainAppliesTheRenameWhateverTheVersion() {
        ScenarioMigrations.Migrated migrated = ScenarioMigrations.migrate(
                withRules(Map.of("desactivees", List.of("travailContinuMaxMajeur", "travailContinuMaxMineur"))),
                ScenarioBinder.SCHEMA_VERSION);

        assertThat(rulesOf(migrated.document()).get("desactivees")).isEqualTo(List.of("travailContinuMax"));
        assertThat(migrated.warnings()).hasSize(2);
    }
}
