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
 * The timeslot grid read from a file, end to end.
 *
 * <p>What it pins beyond the happy path: the grid has no identifier a file can
 * carry, so a row is matched on {@code (date, début, fin)} and a replayed file
 * updates instead of duplicating; a timeslot the file leaves out survives; and
 * every dialect a spreadsheet writes a date or an hour in is read rather than
 * refused.</p>
 */
@QuarkusTest
class CreneauCsvImportServiceTest {

    private static final String HEADER = "X-Edition-Id";
    /** Drawn by the application when the edition is created (ADR 0050). */
    private static String EDITION;

    @BeforeEach
    void createTheEdition() {
        EDITION = given().contentType("application/json")
                .body("{\"nom\":\"Import CSV créneaux\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void dropTheEdition() {
        given().when().delete("/api/editions/" + EDITION);
    }

    private static io.restassured.response.Response poster(String chemin, String contenu) {
        return given().header(HEADER, EDITION)
                .contentType("application/json")
                .body("{\"fileName\":\"creneaux.csv\",\"content\":" + quote(contenu) + "}")
                .when()
                .post(chemin);
    }

    private static String quote(String texte) {
        return "\"" + texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static JsonPath grille() {
        return given().header(HEADER, EDITION)
                .when()
                .get("/api/creneaux")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void thePreviewWritesNothingAndTheImportFillsTheGrid() {
        String fichier = """
                date;heureDebut;heureFin;couverturePause
                2026-07-10;09:00;12:00;
                2026-07-10;12:00;13:00;oui
                2026-07-10;13:00;20:00;
                """;

        poster("/api/creneaux/import-csv/analyse", fichier)
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("cible", equalTo("CRENEAUX"))
                .body("total", equalTo(3))
                .body("created", equalTo(3))
                .body("rows[1].details[0]", containsString("Relais repas"));
        assertThat(grille().getList("$")).isEmpty();

        poster("/api/creneaux/import-csv", fichier)
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("created", equalTo(3));

        JsonPath ecrite = grille();
        assertThat(ecrite.getList("$")).hasSize(3);
        assertThat(ecrite.getBoolean("find { it.heureDebut == '12:00:00' }.couverturePause"))
                .isTrue();
        assertThat(ecrite.getBoolean("find { it.heureDebut == '09:00:00' }.couverturePause"))
                .isFalse();
    }

    /**
     * The whole point of matching on the natural key: a timeslot has no id of
     * its own, so a file replayed — or corrected in a spreadsheet and sent
     * again — must write over what is there instead of doubling the grid.
     */
    @Test
    void aReplayedFileUpdatesTheSameTimeslotsInsteadOfDuplicatingThem() {
        String fichier = "date;heureDebut;heureFin;couverturePause\n2026-07-10;09:00;12:00;\n";
        poster("/api/creneaux/import-csv", fichier).then().statusCode(200);

        poster("/api/creneaux/import-csv", "date;heureDebut;heureFin;couverturePause\n2026-07-10;09:00;12:00;oui\n")
                .then()
                .statusCode(200)
                .body("created", equalTo(0))
                .body("updated", equalTo(1));

        JsonPath ecrite = grille();
        assertThat(ecrite.getList("$")).hasSize(1);
        assertThat(ecrite.getBoolean("[0].couverturePause")).isTrue();
    }

    /** The doctrine of every CSV import here: a file covering one day empties nothing else. */
    @Test
    void aTimeslotTheFileLeavesOutIsUntouched() {
        given().header(HEADER, EDITION)
                .contentType("application/json")
                .body("{\"date\":\"2026-07-09\",\"heureDebut\":\"10:00\",\"heureFin\":\"18:00\"}")
                .when()
                .post("/api/creneaux")
                .then()
                .statusCode(200);

        poster("/api/creneaux/import-csv", "date;heureDebut;heureFin\n2026-07-10;09:00;12:00\n")
                .then()
                .statusCode(200)
                .body("created", equalTo(1));

        assertThat(grille().getList("$")).hasSize(2);
    }

    @Test
    void readsTheDateAndHourDialectsASpreadsheetWrites() {
        poster("/api/creneaux/import-csv", """
                        date;heureDebut;heureFin
                        10/07/2026;9h;12h30
                        11.07.2026;09:00:00;12:00:00
                        12/07/26;9;12
                        """)
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("created", equalTo(3));

        JsonPath ecrite = grille();
        assertThat(ecrite.getList("$")).hasSize(3);
        assertThat(ecrite.getString("find { it.date == '2026-07-10' }.heureFin"))
                .isEqualTo("12:30:00");
        // A two-digit year on an event date is read ahead, not a century back.
        assertThat(ecrite.getList("findAll { it.date == '2026-07-12' }")).hasSize(1);
    }

    /** A night vacation is written exactly as the form writes it: the end is the next day. */
    @Test
    void aTimeslotCrossingMidnightIsAcceptedAndSaidSo() {
        poster("/api/creneaux/import-csv/analyse", "date;heureDebut;heureFin\n2026-07-10;20:00;00:00\n")
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("rows[0].details[0]", containsString("lendemain"));
    }

    @Test
    void badRowsAreRefusedOneByOneWithoutBlockingTheOthers() {
        JsonPath rapport = poster("/api/creneaux/import-csv/analyse", """
                        date;heureDebut;heureFin
                        2026-07-10;09:00;12:00
                        ;09:00;12:00
                        pas-une-date;09:00;12:00
                        2026-07-10;midi;12:00
                        2026-07-10;09:00;
                        2026-07-10;14:00;14:00
                        2026-07-10;09:00;12:00
                        """)
                .then()
                .statusCode(200)
                .body("accepted", equalTo(1))
                .body("rejected", equalTo(6))
                .extract()
                .jsonPath();

        assertThat(rapport.getString("rows.find { it.line == 3 }.raisons[0]")).contains("date est absente");
        assertThat(rapport.getString("rows.find { it.line == 4 }.raisons[0]")).contains("Date illisible");
        assertThat(rapport.getString("rows.find { it.line == 5 }.raisons[0]")).contains("heureDebut");
        assertThat(rapport.getString("rows.find { it.line == 6 }.raisons[0]")).contains("heureFin");
        assertThat(rapport.getString("rows.find { it.line == 7 }.raisons[0]")).contains("pas de durée");
        assertThat(rapport.getString("rows.find { it.line == 8 }.raisons[0]")).contains("déjà plus haut");
    }

    /**
     * A row refused for its own reason must not claim the key it named: the
     * next row saying the same thing correctly would then be turned away as a
     * duplicate of something that was never written.
     */
    @Test
    void aRefusedRowDoesNotTurnTheNextGoodOneIntoADuplicate() {
        poster("/api/creneaux/import-csv/analyse", """
                        date;heureDebut;heureFin
                        2026-07-10;09:00;09:00
                        2026-07-10;09:00;12:00
                        """)
                .then()
                .statusCode(200)
                .body("accepted", equalTo(1))
                .body("rejected", equalTo(1))
                .body("rows.find { it.line == 3 }.action", equalTo("CREE"));
    }

    @Test
    void aFileWithoutTheExpectedColumnsIsRefusedByNamingThem() {
        poster("/api/creneaux/import-csv/analyse", "jour;debut;fin\n2026-07-10;09:00;12:00\n")
                .then()
                .statusCode(400)
                .body("message", containsString("date"))
                .body("message", containsString("heureDebut"));
    }

    @Test
    void theDownloadedExampleIsReadBackByTheImport() {
        String exemple = given().header(HEADER, EDITION)
                .when()
                .get("/api/creneaux/import-csv/exemple")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        poster("/api/creneaux/import-csv/analyse", exemple.replace("﻿", ""))
                .then()
                .statusCode(200)
                .body("rejected", equalTo(0))
                .body("rows", hasSize(4));
    }
}
