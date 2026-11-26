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

    /** The two rules that kept their name vote with the retired one: unwritten, they are on. */
    @Test
    void aRuleThatKeptItsNameVotesWithTheRetiredOne() {
        List<String> warnings = new java.util.ArrayList<>();

        // the minors' half off alone: the adults' half, unwritten, is on -> the merged rule stays on
        Map<String, Object> aloneOff = ScenarioMigrations.renameRetiredRules(
                withRules(Map.of("desactivees", List.of("reposHebdomadaireMineur"))), warnings);
        assertThat(rulesOf(aloneOff).get("desactivees")).isEqualTo(List.of());
        assertThat(warnings).singleElement().asString().contains("reposHebdomadaireMineur", "reposHebdomadaireMinimal");

        // both halves off: off, under the kept name, weights and levels folded on the strongest
        Map<String, Object> bothOff = ScenarioMigrations.renameRetiredRules(
                withRules(Map.of(
                        "desactivees", List.of("dureeHebdomadaireMax", "dureeHebdomadaireMaxMineur"),
                        "poids", Map.of("dureeHebdomadaireMax", 3, "dureeHebdomadaireMaxMineur", 25),
                        "niveaux",
                                Map.of(
                                        "dureeHebdomadaireMax", "PREFERENCE",
                                        "dureeHebdomadaireMaxMineur", "OBLIGATOIRE"))),
                new java.util.ArrayList<>());
        assertThat(rulesOf(bothOff).get("desactivees")).isEqualTo(List.of("dureeHebdomadaireMax"));
        assertThat(rulesOf(bothOff).get("poids")).isEqualTo(Map.of("dureeHebdomadaireMax", 25));
        assertThat(rulesOf(bothOff).get("niveaux")).isEqualTo(Map.of("dureeHebdomadaireMax", "OBLIGATOIRE"));
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

    @Test
    void theHardRunFormSwitchedOnIsReadAsTheRuleMadeObligatoire() {
        ScenarioMigrations.Migrated migrated = ScenarioMigrations.migrate(
                withRules(Map.of(
                        "activees", List.of("maxJoursConsecutifsTravaillesDur"),
                        "desactivees", List.of("maxJoursConsecutifsTravailles", "reposQuotidienMinimal"),
                        "poids", Map.of("maxJoursConsecutifsTravailles", 25, "maxJoursConsecutifsTravaillesDur", 1))),
                ScenarioBinder.SCHEMA_VERSION);
        Map<String, Object> converted = migrated.document();

        assertThat(rulesOf(converted).get("activees")).isEqualTo(List.of());
        assertThat(rulesOf(converted).get("desactivees")).isEqualTo(List.of("reposQuotidienMinimal"));
        assertThat(rulesOf(converted).get("poids")).isEqualTo(Map.of("maxJoursConsecutifsTravailles", 25));
        assertThat(rulesOf(converted).get("niveaux")).isEqualTo(Map.of("maxJoursConsecutifsTravailles", "OBLIGATOIRE"));
        assertThat(migrated.warnings())
                .singleElement()
                .asString()
                .contains("maxJoursConsecutifsTravaillesDur")
                .contains("obligatoire");
    }

    @Test
    void theHardRunFormSwitchedOffChangesNothingButIsSaid() {
        List<String> warnings = new java.util.ArrayList<>();

        Map<String, Object> converted = ScenarioMigrations.foldHardRunForm(
                withRules(Map.of(
                        "desactivees", List.of("maxJoursConsecutifsTravaillesDur"),
                        "poids", Map.of("maxJoursConsecutifsTravailles", 5))),
                warnings);

        assertThat(rulesOf(converted).get("desactivees")).isEqualTo(List.of());
        assertThat(rulesOf(converted)).doesNotContainKey("niveaux");
        assertThat(rulesOf(converted).get("poids")).isEqualTo(Map.of("maxJoursConsecutifsTravailles", 5));
        assertThat(warnings).singleElement().asString().contains("maxJoursConsecutifsTravaillesDur");
    }

    @Test
    void aDocumentWithoutTheHardRunFormIsLeftAlone() {
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Object> document = withRules(Map.of("desactivees", List.of("maxJoursConsecutifsTravailles")));

        assertThat(ScenarioMigrations.foldHardRunForm(document, warnings)).isSameAs(document);
        assertThat(warnings).isEmpty();
    }

    /**
     * A 3.x file carrying the two retired ad hoc types (ADR 0117) reads as what
     * replaces them, as migration V161 converts the database: a forced
     * unavailability is a day off on the fiche, a forced assignment a lock on
     * the seat the plan of the file holds, and everything that converts to
     * nothing is said by exception id.
     */
    @Test
    void theRetiredAdHocTypesAreReadAsDaysOffAndLocksAndEachConversionIsSaid() {
        String yaml = """
                schemaVersion: 3
                animateurs:
                - {id: A1, prenom: Alice, nom: Un, dateNaissance: 1990-01-01, joursIndisponibles: [2026-07-03]}
                - {id: A2, prenom: Bob, nom: Deux, dateNaissance: 1990-01-01}
                - {id: A3, prenom: Chloe, nom: Trois, dateNaissance: 1990-01-01}
                - {id: A4, prenom: Dan, nom: Quatre, dateNaissance: 1990-01-01}
                creneaux:
                - {id: C1, jour: 1, date: 2026-07-01, heureDebut: "09:00", heureFin: "12:00"}
                - {id: C2, jour: 2, date: 2026-07-02, heureDebut: "09:00", heureFin: "12:00"}
                contraintesAdHoc:
                - {id: I1, type: INDISPONIBILITE_FORCEE, animateurs: [A1], creneauId: C1}
                - {id: I2, type: INDISPONIBILITE_FORCEE, animateurs: [A2]}
                - {id: I3, type: INDISPONIBILITE_FORCEE, animateurs: [A3], standId: S1}
                - {id: F1, type: AFFECTATION_FORCEE, animateurs: [A3, A4], creneauId: C1, raison: promis}
                - {id: F2, type: AFFECTATION_FORCEE, animateurs: [A4], creneauId: C2}
                - {id: K1, type: INCOMPATIBILITE, animateurs: [A1, A2]}
                plan:
                  affectations:
                  - {posteId: P1, standId: S1, date: 2026-07-01, heureDebut: "09:00", heureFin: "12:00", animateurId: A3}
                """;

        ScenarioBinder.Bound bound = ScenarioBinder.bindWithWarnings(yaml);

        assertThat(bound.scenario().contraintesAdHoc())
                .extracting(c -> c.id() + "|" + c.type())
                .containsExactly("K1|INCOMPATIBILITE");
        assertThat(bound.scenario().animateurs())
                .extracting(a -> a.id() + "|" + a.joursIndisponibles())
                .containsExactly("A1|[2026-07-01, 2026-07-03]", "A2|[2026-07-01, 2026-07-02]", "A3|null", "A4|null");
        assertThat(bound.scenario().plan().verrous())
                .extracting(v -> v.type() + "|" + v.animateurId() + "|" + v.creneauDate() + "|" + v.raison())
                .containsExactly("ANIMATEUR_CRENEAU|A3|2026-07-01|promis");
        assertThat(bound.scenario().plan().affectations()).hasSize(1);
        assertThat(bound.warnings())
                .hasSize(5)
                .anyMatch(w -> w.startsWith("Exception I1") && w.contains("JOUR ENTIER"))
                .anyMatch(w -> w.startsWith("Exception I2") && w.contains("tous les jours"))
                .anyMatch(w -> w.startsWith("Exception I3") && w.contains("n'est pas reprise"))
                .anyMatch(w -> w.startsWith("Exception F1") && w.contains("1 verrou"))
                .anyMatch(w -> w.startsWith("Exception F2") && w.contains("n'est pas reprise"));
    }
}
