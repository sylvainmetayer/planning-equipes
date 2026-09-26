package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code contraintes:} section of a scenario, from the YAML down to the
 * catalogue the solver reads.
 *
 * <p>Until this section existed, a scenario file carried the legal, cutting and
 * solver parameters but neither the toggles nor the weights: two editions fed
 * the <em>same</em> file could solve measurably different problems, and nothing
 * in the file said so. What is tested here is that the file now decides.</p>
 *
 * <p>And that it decides <b>as a block</b>. A rule the section does not name
 * goes back to active, at its default weight — it is not left as the importing
 * edition happened to have it. Merging would be the friendlier behaviour for
 * someone expecting a patch, and the wrong one: it would let leftovers of the
 * destination survive an import, which is exactly the silence this section was
 * added to break. That choice is the reason for the last two tests.</p>
 */
@QuarkusTest
class ImportScenarioContraintesTest {

    private static final String HEADER = "X-Edition-Id";
    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String EDITION;

    private static final String ENTETE = """
            festival:
              dateDebut: 2026-07-08

            creneaux:
              - id: J1-MATIN
                jour: 1
                date: 2026-07-08
                heureDebut: "09:00"
                heureFin: "13:00"

            stands:
              - id: STAND-STRAT
                nom: Stand stratégie
                typologiesProposees:
                  - STRATEGIE
                effectifMin: 1
                effectifMax: 2
                reserveMajeurs: false

            animateurs:
              - id: A1
                prenom: Alice
                nom: Referente
                dateNaissance: 2002-07-19
                manager: false
                competences:
                  STRATEGIE: REFERENT
                joursIndisponibles: []
            """;

    /** Switches off one rule and weighs two others. */
    private static final String AVEC_CONTRAINTES = """
            contraintes:
              desactivees:
                - eviterRoulementStandsPremium
              poids:
                equilibrerCharge: 7
                maxJoursConsecutifsTravailles: 3

            """ + ENTETE;

    /** Names a different rule, and no weight at all. */
    private static final String AVEC_AUTRES_CONTRAINTES = """
            contraintes:
              desactivees:
                - limiterEmplacementsParJour
              poids: {}

            """ + ENTETE;

    /** Carries no `contraintes:` section whatsoever. */
    private static final String SANS_SECTION = ENTETE;

    @BeforeEach
    void creerLEditionDAtterrissage() {
        EDITION = given().contentType("application/json")
                .body("{\"nom\":\"Import contraintes\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void supprimerLEditionDAtterrissage() {
        given().when().delete("/api/editions/" + EDITION);
    }

    private void importer(String yaml) {
        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(200);
    }

    private List<Map<String, Object>> contraintes() {
        return given().header(HEADER, EDITION)
                .when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("contraintes");
    }

    private Map<String, Object> contrainte(String nom) {
        return contraintes().stream()
                .filter(c -> nom.equals(c.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("contrainte absente du catalogue : " + nom));
    }

    @Test
    void theFileSectionSwitchesOffTheRulesItNames() {
        importer(AVEC_CONTRAINTES);

        assertThat(contrainte("eviterRoulementStandsPremium")).containsEntry("actif", false);
        assertThat(contrainte("equilibrerCharge")).containsEntry("actif", true);
    }

    @Test
    void theFileSectionSetsTheWeightsItGives() {
        importer(AVEC_CONTRAINTES);

        assertThat(contrainte("equilibrerCharge")).containsEntry("poids", 7);
        assertThat(contrainte("maxJoursConsecutifsTravailles")).containsEntry("poids", 3);
        // A rule the section does not weigh keeps the deployment default: the « normale » position.
        assertThat(contrainte("limiterEmplacementsParJour")).containsEntry("poids", 5);
    }

    @Test
    void aSecondImportSwitchesBackOnWhatTheFirstSwitchedOff() {
        importer(AVEC_CONTRAINTES);
        assertThat(contrainte("eviterRoulementStandsPremium")).containsEntry("actif", false);

        importer(AVEC_AUTRES_CONTRAINTES);

        // As a block, not as a merge: the rule the first file switched off is
        // not named by the second, so it comes back active. Otherwise the
        // "same" scenario would keep solving a different problem depending on
        // what the destination edition happened to carry.
        assertThat(contrainte("eviterRoulementStandsPremium")).containsEntry("actif", true);
        assertThat(contrainte("limiterEmplacementsParJour")).containsEntry("actif", false);
    }

    @Test
    void aSecondImportRestoresTheDefaultWeightOfTheRulesItDoesNotWeigh() {
        importer(AVEC_CONTRAINTES);
        assertThat(contrainte("equilibrerCharge")).containsEntry("poids", 7);

        importer(AVEC_AUTRES_CONTRAINTES);

        // Back to the *deployment* default: the edition's own row is deleted,
        // not overwritten with a literal, so the weight is whatever
        // application.properties says — a uniform « normale » 5 for a quality
        // rule since the two business ratios moved to the scenarios that need them.
        assertThat(contrainte("equilibrerCharge")).containsEntry("poids", 5);
        assertThat(contrainte("maxJoursConsecutifsTravailles")).containsEntry("poids", 5);
    }

    @Test
    void aFileWithoutTheSectionTouchesNothing() {
        importer(AVEC_CONTRAINTES);

        importer(SANS_SECTION);

        // Absent is not empty: a file written before the section existed must
        // not wipe the settings of the edition receiving it. Only a file that
        // takes a position decides.
        assertThat(contrainte("eviterRoulementStandsPremium")).containsEntry("actif", false);
        assertThat(contrainte("equilibrerCharge")).containsEntry("poids", 7);
    }

    @Test
    void unPoidsInvalideDansLeFichierFaitEchouerLimportAuLieuDetreIgnore() {
        String yaml = """
                contraintes:
                  desactivees: []
                  poids:
                    equilibrerCharge: 0

                """ + ENTETE;

        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400);
    }

    @Test
    void unNomDeRegleInconnuDansLeFichierFaitEchouerLimport() {
        String yaml = """
                contraintes:
                  desactivees:
                    - regleQuiNexistePas
                  poids: {}

                """ + ENTETE;

        // Ignoring it silently would turn a typo into a still-active rule,
        // with nothing to say so.
        given().header(HEADER, EDITION)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(400);
    }
}
