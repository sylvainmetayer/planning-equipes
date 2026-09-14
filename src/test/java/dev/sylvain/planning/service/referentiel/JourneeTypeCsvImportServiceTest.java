package dev.sylvain.planning.service.referentiel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Day templates and their calendar read from a file, end to end.
 *
 * <p>The rule the whole tab hangs on, and the first thing asserted here:
 * <b>the grid does not move</b>. A template is a generator, never the truth
 * (ADR 0032), so the import writes templates and dates and leaves
 * materialising them to the explicit « Appliquer », which previews what it
 * would change first.</p>
 */
@QuarkusTest
class JourneeTypeCsvImportServiceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String EDITION = "IMPORT-CSV-JT";

    @BeforeEach
    void createTheEdition() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Import CSV journées types\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void dropTheEdition() {
        given().when().delete("/api/editions/" + EDITION);
    }

    private static io.restassured.response.Response poster(String chemin, String contenu) {
        return given().header(HEADER, EDITION)
                .contentType("application/json")
                .body("{\"fileName\":\"journees-types.csv\",\"content\":" + quote(contenu) + "}")
                .when()
                .post(chemin);
    }

    private static String quote(String texte) {
        return "\"" + texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static JsonPath etat() {
        return given().header(HEADER, EDITION)
                .when()
                .get("/api/journees-types")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void thePreviewWritesNothingAndTheImportWritesTemplatesAndTheirCalendar() {
        String fichier = "nom;vacations;dates\n"
                + "Jour normal;\"09:00-12:00, 12:00-13:00 R, 13:00-20:00\";2026-07-10|2026-07-11\n"
                + "Nocturne;\"14:00-20:00, 20:00-00:00\";2026-07-12\n";

        poster("/api/journees-types/import-csv/analyse", fichier)
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("cible", equalTo("JOURNEES_TYPES"))
                .body("created", equalTo(2))
                .body("rows[0].libelle", equalTo("09:00-12:00, 12:00-13:00 R, 13:00-20:00"))
                .body("rows[0].details[0]", containsString("2 date(s)"));
        assertThat(etat().getList("journeesTypes")).isEmpty();

        poster("/api/journees-types/import-csv", fichier)
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("created", equalTo(2));

        JsonPath ecrit = etat();
        assertThat(ecrit.getList("journeesTypes")).hasSize(2);
        assertThat(ecrit.getList("calendrier")).hasSize(3);
        assertThat(ecrit.getList("find { it.nom == 'Jour normal' }.vacations")).isNull();
        assertThat(ecrit.getList("journeesTypes.find { it.nom == 'Jour normal' }.vacations"))
                .hasSize(3);
        assertThat(ecrit.getBoolean("journeesTypes.find { it.nom == 'Jour normal' }.vacations[1].couverturePause"))
                .isTrue();
    }

    /** ADR 0032, held by the import: nothing is materialised until somebody applies it. */
    @Test
    void theGridDoesNotMove() {
        poster(
                        "/api/journees-types/import-csv",
                        "nom;vacations;dates\nJour normal;\"09:00-12:00, 13:00-20:00\";2026-07-10\n")
                .then()
                .statusCode(200);

        given().header(HEADER, EDITION).when().get("/api/creneaux").then().body("$", hasSize(0));

        // And the gesture that does move it still works, on what the file wrote.
        given().header(HEADER, EDITION)
                .contentType("application/json")
                .when()
                .post("/api/journees-types/application")
                .then()
                .statusCode(200)
                .body("crees", equalTo(2));
        given().header(HEADER, EDITION).when().get("/api/creneaux").then().body("$", hasSize(2));
    }

    /** The calendar is merged, not replaced: a date the file does not name keeps what it had. */
    @Test
    void theCalendarIsMergedAndADateNamedTwiceChangesTemplate() {
        poster(
                        "/api/journees-types/import-csv",
                        "nom;vacations;dates\n"
                                + "Jour normal;\"09:00-20:00\";2026-07-10|2026-07-11\n"
                                + "Nocturne;\"14:00-00:00\";2026-07-12\n")
                .then()
                .statusCode(200);

        // A second file naming only one of those dates, for the other template.
        poster("/api/journees-types/import-csv/analyse", "nom;vacations;dates\nNocturne;\"14:00-00:00\";2026-07-11\n")
                .then()
                .statusCode(200)
                .body("updated", equalTo(1))
                .body("rows[0].details[1]", containsString("passe de « Jour normal » à « Nocturne »"));

        poster("/api/journees-types/import-csv", "nom;vacations;dates\nNocturne;\"14:00-00:00\";2026-07-11\n")
                .then()
                .statusCode(200);

        JsonPath ecrit = etat();
        assertThat(ecrit.getList("calendrier")).hasSize(3);
        long nocturne = ecrit.getLong("journeesTypes.find { it.nom == 'Nocturne' }.id");
        long normal = ecrit.getLong("journeesTypes.find { it.nom == 'Jour normal' }.id");
        assertThat(ecrit.getLong("calendrier.find { it.date == '2026-07-11' }.journeeTypeId"))
                .isEqualTo(nocturne);
        // Untouched by the second file, and still governed.
        assertThat(ecrit.getLong("calendrier.find { it.date == '2026-07-10' }.journeeTypeId"))
                .isEqualTo(normal);
    }

    @Test
    void badRowsAreRefusedOneByOneWithoutBlockingTheOthers() {
        JsonPath rapport = poster(
                        "/api/journees-types/import-csv/analyse",
                        "nom;vacations;dates\n"
                                + "Bonne;\"09:00-12:00\";2026-07-10\n"
                                + ";\"09:00-12:00\";\n"
                                + "Sans vacation;;\n"
                                + "Illisible;\"neuf heures à midi\";\n"
                                + "Nulle;\"09:00-09:00\";\n"
                                + "Bonne;\"14:00-18:00\";\n"
                                + "Autre;\"14:00-18:00\";2026-07-10\n"
                                + "Date;\"14:00-18:00\";pas-une-date\n")
                .then()
                .statusCode(200)
                .body("accepted", equalTo(1))
                .body("rejected", equalTo(7))
                .extract()
                .jsonPath();

        assertThat(rapport.getString("rows.find { it.line == 3 }.raisons[0]")).contains("nom");
        assertThat(rapport.getString("rows.find { it.line == 4 }.raisons[0]")).contains("vacations est requis");
        assertThat(rapport.getString("rows.find { it.line == 5 }.raisons[0]")).contains("Vacation invalide");
        assertThat(rapport.getString("rows.find { it.line == 6 }.raisons[0]")).contains("durée nulle");
        assertThat(rapport.getString("rows.find { it.line == 7 }.raisons[0]")).contains("déjà plus haut");
        assertThat(rapport.getString("rows.find { it.line == 8 }.raisons[0]")).contains("déjà affecté");
        assertThat(rapport.getString("rows.find { it.line == 9 }.raisons[0]")).contains("Date illisible");
    }

    /**
     * The comma is what makes this file a trap: the shift column carries more
     * of them than the header carries semicolons, and an unquoted cell would
     * make the reader take the whole file for a comma-separated one.
     */
    @Test
    void aShiftColumnFullOfCommasIsStillReadWithTheRightSeparator() {
        poster(
                        "/api/journees-types/import-csv/analyse",
                        "nom;vacations;dates\n"
                                + "A;\"09:00-10:00, 10:00-11:00, 11:00-12:00, 12:00-13:00\";\n"
                                + "B;\"09:00-10:00, 10:00-11:00, 11:00-12:00, 12:00-13:00\";\n")
                .then()
                .statusCode(200)
                .body("separator", equalTo(";"))
                .body("rejected", equalTo(0))
                .body("created", equalTo(2));
    }

    /** Same rule as the timeslot tab: a refused row claims neither its name nor its dates. */
    @Test
    void aRefusedRowClaimsNeitherItsNameNorItsDates() {
        poster(
                        "/api/journees-types/import-csv/analyse",
                        "nom;vacations;dates\n"
                                + "Jour normal;\"09:00-09:00\";2026-07-10\n"
                                + "Jour normal;\"09:00-20:00\";2026-07-10\n")
                .then()
                .statusCode(200)
                .body("accepted", equalTo(1))
                .body("rejected", equalTo(1))
                .body("rows.find { it.line == 3 }.action", equalTo("CREE"));
    }

    @Test
    void aFileWithoutTheExpectedColumnsIsRefusedByNamingThem() {
        poster("/api/journees-types/import-csv/analyse", "libelle;horaires\nA;B\n")
                .then()
                .statusCode(400)
                .body("message", containsString("nom"))
                .body("message", containsString("vacations"));
    }

    @Test
    void theDownloadedExampleIsReadBackByTheImport() {
        String exemple = given().header(HEADER, EDITION)
                .when()
                .get("/api/journees-types/import-csv/exemple")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        poster("/api/journees-types/import-csv/analyse", exemple.replace("﻿", ""))
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("rows", hasSize(3));
    }
}
