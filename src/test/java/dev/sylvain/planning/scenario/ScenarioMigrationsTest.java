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

    /**
     * The {@code plan:} section of a file written before ADR 0109 — a copy
     * taken before a wipe by an earlier 4.0 build — names locks of a retired
     * type: they are read as what they froze on that plan, as migration V151
     * converts the database, and each conversion is said.
     */
    @Test
    void aPlanSectionLockOfARetiredTypeIsReadAsTheSeatsItFroze() {
        String yaml = """
                schemaVersion: 3
                plan:
                  affectations:
                  - {posteId: P1, standId: S1, date: 2026-07-01, heureDebut: "09:00", heureFin: "12:00", animateurId: A1}
                  - {posteId: P2, standId: S1, date: 2026-07-02, heureDebut: "09:00", heureFin: "12:00", animateurId: A1}
                  - {posteId: P3, standId: S2, date: 2026-07-01, heureDebut: "14:00", heureFin: "17:00", animateurId: A2}
                  - {posteId: P4, standId: S2, date: 2026-07-01, heureDebut: "09:00", heureFin: "12:00"}
                  verrous:
                  - {type: ANIMATEUR, animateurId: A1, raison: gardé}
                  - {type: STAND, standId: S1}
                  - {type: CRENEAU, creneauDate: 2026-07-01, creneauHeureDebut: "14:00", creneauHeureFin: "17:00"}
                  - {type: ANIMATEUR, animateurId: A9}
                  - {type: JOUR, jour: 2026-07-02}
                """;

        ScenarioBinder.Bound bound = ScenarioBinder.bindWithWarnings(yaml);

        assertThat(bound.scenario().plan().verrous().stream()
                        .map(v -> v.type() + "|" + v.animateurId() + "|" + v.standId() + "|" + v.creneauDate() + "|"
                                + v.creneauHeureDebut() + "|" + v.jour() + "|" + v.raison()))
                .containsExactly(
                        "ANIMATEUR_CRENEAU|A1|null|2026-07-01|09:00|null|gardé",
                        "ANIMATEUR_CRENEAU|A1|null|2026-07-02|09:00|null|gardé",
                        "STAND_JOUR|null|S1|null|null|2026-07-01|null",
                        "STAND_JOUR|null|S1|null|null|2026-07-02|null",
                        "ANIMATEUR_CRENEAU|A2|null|2026-07-01|14:00|null|null",
                        "JOUR|null|null|null|null|2026-07-02|null");
        assertThat(bound.scenario().plan().affectations()).hasSize(4);
        assertThat(bound.warnings())
                .hasSize(4)
                .anyMatch(w -> w.startsWith("Verrou ANIMATEUR (A9)") && w.contains("n'est pas repris"));
    }

    @Test
    void aPlanSectionWithoutARetiredLockIsLeftAlone() {
        Map<String, Object> document = document();
        document.put("plan", Map.of("verrous", List.of(Map.of("type", "JOUR", "jour", "2026-07-02"))));
        List<String> warnings = new java.util.ArrayList<>();

        assertThat(ScenarioMigrations.withRetiredLockTypesConverted(document, warnings))
                .isSameAs(document);
        assertThat(warnings).isEmpty();
    }
}
