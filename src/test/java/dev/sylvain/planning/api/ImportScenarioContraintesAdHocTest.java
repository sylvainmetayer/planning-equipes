package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code contraintesAdHoc:} section of a scenario: what it installs, and
 * what a file carrying no section installs — nothing.
 *
 * <p>An import writes the whole set in one go, which is precisely how a
 * combination the form refuses would otherwise get in — and once in, it makes
 * every solve of that edition end hard-negative with nothing naming the
 * cause. A file may not install what the form refuses (issue #84), so the
 * import is refused as a whole, before anything is written.</p>
 *
 * <p>The last test covers the opposite leak: a section-less file used to fall
 * back on the <em>current</em> edition's exceptions and write them into the
 * target one, which crossed the edition boundary and failed on a foreign key
 * as soon as the target held neither the stand nor the créneau they name.</p>
 */
@QuarkusTest
class ImportScenarioContraintesAdHocTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String NOM_EDITION = "Import contraintes ad hoc";
    private static final String NOM_EDITION_CIBLE = "Édition cible ad hoc";

    /** The landing edition of the running test, created by name: its id is generated (ADR 0050). */
    private String edition;

    private static final String ENTETE = """
            festival:
              dateDebut: 2026-07-08

            creneaux:
              - id: J1-MATIN
                jour: 1
                date: 2026-07-08
                heureDebut: "09:00"
                heureFin: "13:00"
              - id: J1-MIDI
                jour: 1
                date: 2026-07-08
                heureDebut: "12:00"
                heureFin: "16:00"

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
              - id: A2
                prenom: Bob
                nom: Debutant
                dateNaissance: 2001-03-04
                manager: false
                competences:
                  STRATEGIE: DEBUTANT
                joursIndisponibles: []
            """;

    /** A1 is forced onto the very créneau another exception declares them unavailable on. */
    private static final String CONTRADICTOIRE = ENTETE + """

            contraintesAdHoc:
              - id: INDISPO-1
                type: INDISPONIBILITE_FORCEE
                animateurs:
                  - A1
                creneauId: J1-MATIN
                raison: Formation
              - id: FORCE-1
                type: AFFECTATION_FORCEE
                animateurs:
                  - A1
                creneauId: J1-MATIN
                standId: STAND-STRAT
                raison: Promesse faite en juin
            """;

    /** No {@code contraintesAdHoc:} section, and a target edition of its own. */
    private static final String VERS_AUTRE_EDITION = """
            edition:
              nom: Édition cible ad hoc

            """ + ENTETE;

    /** The same two exceptions, on two créneaux that do not overlap. */
    private static final String COHERENT = ENTETE + """

            contraintesAdHoc:
              - id: INDISPO-1
                type: INDISPONIBILITE_FORCEE
                animateurs:
                  - A1
                creneauId: J1-MATIN
                raison: Formation
              - id: FORCE-1
                type: AFFECTATION_FORCEE
                animateurs:
                  - A2
                creneauId: J1-MIDI
                standId: STAND-STRAT
                raison: Promesse faite en juin
            """;

    /**
     * The landing edition is created first, on purpose: an unknown
     * {@code X-Edition-Id} falls back to the default one, and the import would
     * then replace the referential every other test reads.
     */
    @BeforeEach
    void createTheLandingEdition() {
        dropEditionsNamed(NOM_EDITION, NOM_EDITION_CIBLE);
        edition = given().contentType("application/json")
                .body("{\"nom\":\"" + NOM_EDITION + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void dropTheLandingEditions() {
        dropEditionsNamed(NOM_EDITION, NOM_EDITION_CIBLE);
    }

    /** By name: the target edition's id is only known from an import that may have failed half-way. */
    private static void dropEditionsNamed(String... noms) {
        List<Map<String, Object>> editions = given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("");
        for (Map<String, Object> existante : editions) {
            if (List.of(noms).contains(existante.get("nom")) && !Boolean.TRUE.equals(existante.get("defaut"))) {
                given().when().delete("/api/editions/" + existante.get("id"));
            }
        }
    }

    @Test
    void aScenarioWhoseExceptionsContradictEachOtherIsRefused() {
        String message = importer(CONTRADICTOIRE, 400).extract().asString();

        // The exceptions are named by the ids the edition gave them, not by the file's.
        assertThat(message).containsPattern("contrainte C\\d+").contains("indisponible");
    }

    @Test
    void nothingOfARefusedScenarioIsWritten() {
        importer(CONTRADICTOIRE, 400);

        // Refused before the first write: the landing edition, created empty, holds nothing.
        assertThat(contraintesAdHoc()).isEmpty();
    }

    @Test
    void aSectionLessScenarioCarriesNoExceptionOver() {
        // The source edition holds two exceptions; the file names none and
        // lands in another edition. Nothing of them may reach it — neither its
        // stand nor its créneaux exist there.
        importer(COHERENT, 200);
        assertThat(contraintesAdHoc()).hasSize(2);

        String editionCible = importer(VERS_AUTRE_EDITION, 200).extract().path("editionId");
        assertThat(editionCible).isNotNull().isNotEqualTo(edition);

        assertThat(given().header(HEADER, editionCible)
                        .when()
                        .get("/api/contraintes-ad-hoc")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("id"))
                .isEmpty();
        // …and the edition that owns them keeps them.
        assertThat(contraintesAdHoc()).hasSize(2);
    }

    @Test
    void aConsistentScenarioGoesThrough() {
        importer(COHERENT, 200);

        // The file's ids are local references: the exceptions are recognised by what they say.
        assertThat(contraintesAdHoc())
                .extracting(contrainte -> contrainte.get("type"), contrainte -> contrainte.get("raison"))
                .containsExactlyInAnyOrder(
                        tuple("INDISPONIBILITE_FORCEE", "Formation"),
                        tuple("AFFECTATION_FORCEE", "Promesse faite en juin"));
        assertThat(contraintesAdHoc())
                .extracting(contrainte -> (String) contrainte.get("id"))
                .allMatch(id -> id.matches("C\\d+"));
    }

    private io.restassured.response.ValidatableResponse importer(String yaml, int statut) {
        return given().header(HEADER, edition)
                .contentType("text/plain")
                .body(yaml)
                .when()
                .post("/api/reference-data/import-scenario-fichier")
                .then()
                .statusCode(statut);
    }

    private List<Map<String, Object>> contraintesAdHoc() {
        return given().header(HEADER, edition)
                .when()
                .get("/api/contraintes-ad-hoc")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }
}
