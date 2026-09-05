package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * {@code /api/stands/import-grille}: the stand matrix previewed, then written
 * in one transaction, and read back as the very grid the entry screen shows.
 */
@QuarkusTest
class StandGrilleImportResourceTest {

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
    }

    private static Map<String, Object> request(String contenu) {
        return Map.of("fileName", "grille.csv", "content", contenu);
    }

    /** The edition's own example re-imports as is: every row accepted, nothing written by the preview. */
    @Test
    void lExempleDeLEditionSeReimporteTelQuel() {
        seedScenario();
        String exemple = given().when().get("/api/stands/import-grille/exemple")
                .then().statusCode(200).contentType(containsString("text/csv"))
                .extract().asString();
        assertThat(exemple).startsWith("stand;");

        JsonPath rapport = given().contentType("application/json").body(request(exemple))
                .when().post("/api/stands/import-grille/analyse")
                .then().statusCode(200)
                .body("applied", equalTo(false))
                .body("rejected", equalTo(0))
                .body("creneauxAbsents", org.hamcrest.Matchers.empty())
                .extract().jsonPath();
        assertThat(rapport.getInt("accepted")).isEqualTo(rapport.getInt("total")).isPositive();
        assertThat(rapport.getList("columns.creneauId", Long.class)).doesNotContainNull();
    }

    @Test
    void uneCelluleModifieeEstEcriteEtRelueDansLaGrille() {
        seedScenario();
        JsonPath avant = given().when().get("/api/ouvertures-stands").then().statusCode(200).extract().jsonPath();
        String standId = avant.getString("stands[0].standId");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        String fin = avant.getString("jours[0].creneaux[0].heureFin").substring(0, 5);
        String csv = "stand;" + date + "\n;" + debut + "-" + fin + "\n" + standId + ";7\n";

        given().contentType("application/json").body(request(csv))
                .when().post("/api/stands/import-grille")
                .then().statusCode(200)
                .body("applied", equalTo(true))
                .body("accepted", equalTo(1))
                .body("rows[0].action", equalTo("UPDATED"))
                .body("rows[0].effectifMax", equalTo(7))
                .body("warnings", notNullValue());

        JsonPath apres = given().when().get("/api/ouvertures-stands").then().statusCode(200).extract().jsonPath();
        assertThat(apres.getInt("stands[0].jours[0].creneaux[0].effectif")).isEqualTo(7);
    }

    @Test
    void unStandInconnuUneCaseIllisibleEtUneColonneSansCreneauSontDits() {
        seedScenario();
        JsonPath avant = given().when().get("/api/ouvertures-stands").then().statusCode(200).extract().jsonPath();
        String standId = avant.getString("stands[0].standId");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        String fin = avant.getString("jours[0].creneaux[0].heureFin").substring(0, 5);
        String csv = "stand;" + date + ";" + date + "\n;" + debut + "-" + fin + ";03:00-04:00\n"
                + standId + ";abc;1\nINCONNU;2;\n";

        JsonPath rapport = given().contentType("application/json").body(request(csv))
                .when().post("/api/stands/import-grille/analyse")
                .then().statusCode(200)
                .body("rejected", equalTo(2))
                .body("columns[1].creneauId", org.hamcrest.Matchers.nullValue())
                .body("columns[1].reason", containsString("ignorée"))
                .extract().jsonPath();
        List<String> motifs = rapport.getList("rows.reasons.flatten()", String.class);
        assertThat(motifs).anySatisfy(motif -> assertThat(motif).contains("abc"));
        assertThat(motifs).anySatisfy(motif -> assertThat(motif).contains("INCONNU"));
    }

    @Test
    void unClasseurEstRefuseAvecLaMarcheASuivre() {
        seedScenario();
        given().contentType("application/json").body(Map.of("fileName", "grille.xlsx", "content", "PK..."))
                .when().post("/api/stands/import-grille/analyse")
                .then().statusCode(400)
                .body("message", containsString("CSV"));
    }

    @Test
    void sansCreneauLImportEstRefuseEnBloc() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().contentType("application/json").body(request("stand;2026-07-08\n;10:00-12:00\nS;1\n"))
                .when().post("/api/stands/import-grille/analyse")
                .then().statusCode(400)
                .body("message", containsString("aucun créneau"));
    }
}
