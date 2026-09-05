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
        // The byte order mark, so Excel reads the file as UTF-8 once it is on
        // disk; the parser strips it, which the clean re-import below proves.
        assertThat(exemple).startsWith("\uFEFFstand;");
        // The band row carries no colon: Excel turns « 13:00-16:00 » into the
        // date-time 13:16:00, and the band is then lost for good.
        String bandes = exemple.lines().skip(1).findFirst().orElseThrow();
        assertThat(bandes).doesNotContain(":").contains("h");

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

    /**
     * Two créneaux of one band, one per stagger family: the column has to land
     * on both. Landing on the first left every stand of the other family
     * untouched, while the report said the row had been written.
     */
    @Test
    void uneColonneSePoseSurLesDeuxFamillesDeLaMemeBande() {
        seedScenario();
        JsonPath avant = given().when().get("/api/ouvertures-stands").then().statusCode(200).extract().jsonPath();
        String standId = avant.getString("stands[0].standId");
        String date = avant.getString("jours[0].date");
        String debut = avant.getString("jours[0].creneaux[0].heureDebut").substring(0, 5);
        String fin = avant.getString("jours[0].creneaux[0].heureFin").substring(0, 5);
        // A twin of that band in the other family — what a staggered grid holds.
        Long jumeau = given().contentType("application/json")
                .body(Map.of("date", date, "heureDebut", debut, "heureFin", fin, "famille", 1))
                .when().post("/api/creneaux").then().statusCode(200)
                .extract().jsonPath().getLong("creneau.id");
        assertThat(jumeau).isNotNull();

        String csv = "stand;" + date + "\n;" + debut + "-" + fin + "\n" + standId + ";7\n";
        JsonPath rapport = given().contentType("application/json").body(request(csv))
                .when().post("/api/stands/import-grille")
                .then().statusCode(200)
                .body("accepted", equalTo(1))
                // Both créneaux of the band, not the first of them.
                .body("columns[0].creneaux", equalTo(2))
                .extract().jsonPath();
        assertThat(rapport.getList("rows.action", String.class)).containsOnly("UPDATED");

        // And neither is left « without a column »: the twin used to be, so its
        // cells were kept as they were while the row was reported as written.
        assertThat(rapport.getList("creneauxAbsents", String.class))
                .doesNotContain(date + " " + debut + "-" + fin);
        JsonPath apres = given().when().get("/api/ouvertures-stands").then().statusCode(200).extract().jsonPath();
        assertThat(apres.getInt("stands[0].jours[0].creneaux[0].effectif")).isEqualTo(7);
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
