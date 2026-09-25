package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/stands/import-grille}: the stand matrix previewed, then written
 * in one transaction, and read back as the very grid the entry screen shows.
 */
@QuarkusTest
class StandGrilleImportResourceTest {

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    private static Map<String, Object> request(String contenu) {
        return Map.of("fileName", "grille.csv", "content", contenu);
    }

    /** The edition's own example re-imports as is: every row accepted, nothing written by the preview. */
    @Test
    void lExempleDeLEditionSeReimporteTelQuel() {
        seedScenario();
        String exemple = given().when()
                .get("/api/stands/import-grille/exemple")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .extract()
                .asString();
        // The byte order mark, so Excel reads the file as UTF-8 once it is on
        // disk; the parser strips it, which the clean re-import below proves.
        assertThat(exemple).startsWith("\uFEFFstand;");
        // The band row carries no colon: Excel turns « 13:00-16:00 » into the
        // date-time 13:16:00, and the band is then lost for good.
        String bandes = exemple.lines().skip(1).findFirst().orElseThrow();
        assertThat(bandes).doesNotContain(":").contains("h");

        JsonPath rapport = given().contentType("application/json")
                .body(request(exemple))
                .when()
                .post("/api/stands/import-grille/analyse")
                .then()
                .statusCode(200)
                .body("applied", equalTo(false))
                .body("rejected", equalTo(0))
                .body("creneauxAbsents", org.hamcrest.Matchers.empty())
                .extract()
                .jsonPath();
        assertThat(rapport.getInt("accepted"))
                .isEqualTo(rapport.getInt("total"))
                .isPositive();
        assertThat(rapport.getList("columns.creneauId", Long.class)).doesNotContainNull();
    }

    @Test
    void anEditedCellIsWrittenAndReadBackInTheGrid() {
        seedScenario();
        JsonPath avant = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String stand = avant.getString("stands[0].nom");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        String fin = avant.getString("jours[0].creneaux[0].heureFin").substring(0, 5);
        String csv = "stand;" + date + "\n;" + debut + "-" + fin + "\n" + stand + ";7\n";

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/stands/import-grille")
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("accepted", equalTo(1))
                .body("rows[0].action", equalTo("UPDATED"))
                .body("rows[0].effectifMax", equalTo(7))
                .body("warnings", notNullValue());

        JsonPath apres = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(apres.getInt("stands[0].jours[0].creneaux[0].effectif")).isEqualTo(7);
    }

    /** The workbook's own columns are narrower than the créneaux: each writes a window at its bounds. */
    @Test
    void aColumnNarrowerThanItsTimeslotWritesItsSliceAndLeavesTheRest() {
        seedScenario();
        JsonPath avant = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String stand = avant.getString("stands[0].nom");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        Integer avantEffectif = avant.get("stands[0].jours[0].creneaux[0].effectif");
        // The first hour of the first créneau only.
        String finTranche = java.time.LocalTime.parse(debut).plusHours(1).toString();
        String csv = "stand;" + date + "\n;" + debut + "-" + finTranche + "\n" + stand + ";7\n";

        given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/stands/import-grille")
                .then()
                .statusCode(200)
                .body("applied", equalTo(true))
                .body("accepted", equalTo(1))
                .body("columns[0].creneauId", notNullValue())
                .body("columns[0].reason", org.hamcrest.Matchers.nullValue());

        JsonPath apres = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        // The créneau now shows two columns: the imported hour at 7, the rest as it was.
        assertThat(apres.getString("jours[0].creneaux[0].heureFin")).startsWith(finTranche);
        assertThat(apres.getInt("jours[0].creneaux[1].tranche")).isEqualTo(1);
        assertThat(apres.getInt("stands[0].jours[0].creneaux[0].effectif")).isEqualTo(7);
        assertThat((Integer) apres.get("stands[0].jours[0].creneaux[1].effectif"))
                .isEqualTo(avantEffectif);
    }

    @Test
    void anUnknownStandAnUnreadableCellAndAColumnWithoutTimeslotAreReported() {
        seedScenario();
        JsonPath avant = given().when()
                .get("/api/ouvertures-stands")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String stand = avant.getString("stands[0].nom");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        String fin = avant.getString("jours[0].creneaux[0].heureFin").substring(0, 5);
        String csv = "stand;" + date + ";" + date + "\n;" + debut + "-" + fin + ";03:00-04:00\n" + stand
                + ";abc;1\nINCONNU;2;\n";

        JsonPath rapport = given().contentType("application/json")
                .body(request(csv))
                .when()
                .post("/api/stands/import-grille/analyse")
                .then()
                .statusCode(200)
                .body("rejected", equalTo(2))
                .body("columns[1].creneauId", org.hamcrest.Matchers.nullValue())
                .body("columns[1].reason", containsString("ignorée"))
                .extract()
                .jsonPath();
        List<String> motifs = rapport.getList("rows.reasons.flatten()", String.class);
        assertThat(motifs)
                .anySatisfy(motif -> assertThat(motif).contains("abc"))
                .anySatisfy(motif -> assertThat(motif).contains("INCONNU"));
    }

    @Test
    void unClasseurEstRefuseAvecLaMarcheASuivre() {
        seedScenario();
        given().contentType("application/json")
                .body(Map.of("fileName", "grille.xlsx", "content", "PK..."))
                .when()
                .post("/api/stands/import-grille/analyse")
                .then()
                .statusCode(400)
                .body("message", containsString("CSV"));
    }

    @Test
    void sansCreneauLImportEstRefuseEnBloc() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().contentType("application/json")
                .body(request("stand;2026-07-08\n;10:00-12:00\nS;1\n"))
                .when()
                .post("/api/stands/import-grille/analyse")
                .then()
                .statusCode(400)
                .body("message", containsString("aucun créneau"));
    }
}
