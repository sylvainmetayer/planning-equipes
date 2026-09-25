package dev.sylvain.planning.service.referentiel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three small referentials read from a file, end to end.
 *
 * <p>What it pins beyond the happy path: the preview writes nothing, a column
 * the file leaves out never erases what a fiche already holds, and a typologie
 * a stand names without it existing is created — and said so before the write.</p>
 */
@QuarkusTest
class ReferentielCsvImportServiceTest {

    private static final String HEADER = "X-Edition-Id";
    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String EDITION;

    @BeforeEach
    void creerLEdition() {
        EDITION = given().contentType("application/json")
                .body("{\"nom\":\"Import CSV référentiels\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void supprimerLEdition() {
        given().when().delete("/api/editions/" + EDITION);
    }

    private static String corps(String contenu) {
        return "{\"fileName\":\"fichier.csv\",\"content\":" + quote(contenu) + "}";
    }

    private static String quote(String texte) {
        return "\"" + texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static io.restassured.response.Response poster(String chemin, String contenu) {
        return given().header(HEADER, EDITION)
                .contentType("application/json")
                .body(corps(contenu))
                .when()
                .post(chemin);
    }

    @Test
    void thePreviewWritesNothingAndTheImportWritesTheThreeReferentials() {
        String typologies = "code;libelle\nAMBIANCE;Jeux d'ambiance\nSTRATEGIE;Jeux de stratégie\n";

        poster("/api/typologies/import-csv/analyse", typologies)
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("cible", equalTo("TYPOLOGIES"))
                .body("total", equalTo(2))
                .body("created", equalTo(2))
                .body("rows[0].action", equalTo("CREE"));
        given().header(HEADER, EDITION)
                .when()
                .get("/api/typologies")
                .then()
                .body("findAll { it.code == 'AMBIANCE' }", hasSize(0));

        poster("/api/typologies/import-csv", typologies)
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("created", equalTo(2));
        given().header(HEADER, EDITION)
                .when()
                .get("/api/typologies")
                .then()
                .body("find { it.code == 'AMBIANCE' }.label", equalTo("Jeux d'ambiance"))
                // The code is what the file names; the id is drawn by the application.
                .body("find { it.code == 'AMBIANCE' }.id", org.hamcrest.Matchers.matchesPattern("T\\d+"));

        // Replayed, the same file updates instead of creating.
        poster("/api/typologies/import-csv", typologies)
                .then()
                .statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(2));

        poster(
                        "/api/emplacements/import-csv",
                        "code;nom;latitude;longitude\nPAV;Pavillon;46,65;-0,24\nEXT;Esplanade;;\n")
                .then()
                .statusCode(200)
                .body("created", equalTo(2))
                .body("rows.find { it.id == 'EXT' }.details[0]", containsString("coordonnées"));
        given().header(HEADER, EDITION)
                .when()
                .get("/api/emplacements")
                .then()
                .body("find { it.code == 'PAV' }.latitude", equalTo(46.65f))
                .body("find { it.code == 'EXT' }.latitude", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void aStandWithoutHeadcountHoldsOnePersonAndItsMissingTypologieIsCreatedAndAnnounced() {
        poster("/api/stands/import-csv/analyse", "code;nom;typologies\nSTAND-UN;Stand un;INCONNUE\n")
                .then()
                .statusCode(200)
                .body("typologiesCreees", hasSize(1))
                .body("typologiesCreees[0]", equalTo("INCONNUE"))
                .body("rows[0].details[0]", containsString("INCONNUE"))
                .body("rows[0].details[1]", containsString("une personne"));

        poster("/api/stands/import-csv", "code;nom;typologies\nSTAND-UN;Stand un;INCONNUE\n")
                .then()
                .statusCode(200)
                .body("created", equalTo(1));

        given().header(HEADER, EDITION)
                .when()
                .get("/api/stands")
                .then()
                .body("find { it.code == 'STAND-UN' }.effectifMin", equalTo(1))
                .body("find { it.code == 'STAND-UN' }.effectifMax", equalTo(1))
                .body("find { it.code == 'STAND-UN' }.typologiesProposees", hasSize(1));
        given().header(HEADER, EDITION)
                .when()
                .get("/api/typologies")
                .then()
                .body("find { it.code == 'INCONNUE' }.label", equalTo("INCONNUE"));
    }

    /**
     * The doctrine of the compétences grid, on a fiche: a three-column file
     * renaming a stand must not take its opening hours with it.
     */
    @Test
    void aColumnLeftOutOfTheFileKeepsWhatTheFicheAlreadyHolds() {
        // The fiche goes through the plain CRUD, which demands a known typologie.
        poster("/api/typologies/import-csv", "code;libelle\nJEU;Jeux\n").then().statusCode(200);
        String stand = given().header(HEADER, EDITION)
                .contentType("application/json")
                .body("""
                        {"nom":"Stand neuf","typologiesProposees":["JEU"],"effectifMin":3,"effectifMax":5,
                         "horaires":[{"mode":"OUVERTURE","jours":"TOUS","fenetres":[{"heureDebut":"10:00:00"}]}]}
                        """)
                .when()
                .post("/api/stands")
                .then()
                .statusCode(200)
                .extract()
                .path("stand.id");

        // Designated by its generated id, as an exported file does.
        poster("/api/stands/import-csv", "id;nom;typologies\n" + stand + ";Stand renommé;JEU\n")
                .then()
                .statusCode(200)
                .body("updated", equalTo(1));

        given().header(HEADER, EDITION)
                .when()
                .get("/api/stands")
                .then()
                .body("find { it.id == '" + stand + "' }.nom", equalTo("Stand renommé"))
                // Neither the headcount nor the schedule was in the file: both stayed.
                .body("find { it.id == '" + stand + "' }.effectifMin", equalTo(3))
                .body("find { it.id == '" + stand + "' }.effectifMax", equalTo(5))
                .body("find { it.id == '" + stand + "' }.horaires", hasSize(1));
    }

    @Test
    void faultyRowsAreRefusedOneByOneWithoutBlockingTheOthers() {
        io.restassured.path.json.JsonPath rapport = poster("/api/stands/import-csv/analyse", """
                        id;nom;typologies;effectifMin;effectifMax
                        OK;Bon stand;JEU;1;2
                        ;Sans id;JEU;;
                        DOUBLON;Un;JEU;;
                        DOUBLON;Deux;JEU;;
                        MAX;Inversé;JEU;4;2
                        NUM;Pas un nombre;JEU;deux;
                        """)
                .then()
                .statusCode(200)
                .body("accepted", equalTo(2))
                .body("rejected", equalTo(4))
                .extract()
                .jsonPath();

        List<Map<String, Object>> refusees = rapport.getList("rows.findAll { it.action == 'REFUSE' }");
        assertThat(refusees).hasSize(4);
        assertThat(rapport.getString("rows.find { it.line == 3 }.raisons[0]")).contains("id");
        assertThat(rapport.getString("rows.find { it.line == 5 }.raisons[0]")).contains("déjà plus haut");
        assertThat(rapport.getString("rows.find { it.line == 6 }.raisons[0]")).contains("inférieur");
        assertThat(rapport.getString("rows.find { it.line == 7 }.raisons[0]")).contains("nombre");
    }

    /**
     * A typologie is created on the strength of the stand that names it, so a
     * refused stand must not leave one behind: the announcement used to be made
     * before the headcount check, and the file above created MAUVAISE for a line
     * nothing would ever reference.
     */
    @Test
    void uneLigneRefuseeNeCreePasLaTypologieQuElleCitait() {
        poster("/api/stands/import-csv/analyse", "id;nom;typologies;effectifMin;effectifMax\nKO;Refusé;MAUVAISE;4;2\n")
                .then()
                .statusCode(200)
                .body("accepted", equalTo(0))
                .body("rejected", equalTo(1))
                .body("typologiesCreees", hasSize(0));

        poster("/api/stands/import-csv", "id;nom;typologies;effectifMin;effectifMax\nKO;Refusé;MAUVAISE;4;2\n")
                .then()
                .statusCode(200)
                .body("created", equalTo(0));

        given().header(HEADER, EDITION)
                .when()
                .get("/api/typologies")
                .then()
                .body("findAll { it.id == 'MAUVAISE' }", hasSize(0));
    }

    @Test
    void unFichierSansLesColonnesAttenduesEstRefuseEnLesNommant() {
        poster("/api/typologies/import-csv/analyse", "identifiant;nom\nA;B\n")
                .then()
                .statusCode(400)
                .body("message", containsString("id"))
                .body("message", containsString("libelle"));
    }

    @Test
    void unTableurEstRefuseAvecLaMarcheASuivre() {
        given().header(HEADER, EDITION)
                .contentType("application/json")
                .body("{\"fileName\":\"stands.xlsx\",\"content\":\"PK...\"}")
                .when()
                .post("/api/stands/import-csv/analyse")
                .then()
                .statusCode(400)
                .body("message", containsString("CSV"));
    }

    @Test
    void lExempleTelechargeSeRelitParLImport() {
        String exemple = given().header(HEADER, EDITION)
                .when()
                .get("/api/typologies/import-csv/exemple")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        poster("/api/typologies/import-csv/analyse", exemple.replace("﻿", ""))
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("total", equalTo(3));
    }
}
