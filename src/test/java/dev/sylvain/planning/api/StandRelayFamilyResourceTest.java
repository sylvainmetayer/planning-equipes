package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relay family is an attribute of the stand (issue #390): given at
 * creation, kept whatever happens to the other stands, chosen by the
 * operator when they want to. In its own edition, with a two-family grid.
 */
@QuarkusTest
class StandRelayFamilyResourceTest {

    private static final String EDITION = "FAMILLES-EDITION";

    @BeforeEach
    void freshEdition() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Édition des familles\"}")
                .when().post("/api/editions");
        edition().when().post("/api/planning/reset").then().statusCode(200);
        // A reset keeps the typologies: the referential of this edition is rebuilt explicitly.
        edition().contentType("text/plain").body(String.join("\n",
                "DELETE FROM typologie WHERE edition_id = '" + EDITION + "';",
                "DELETE FROM creneau WHERE edition_id = '" + EDITION + "';",
                "INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin, famille) VALUES ('" + EDITION + "', '2026-08-14', '09:00', '13:00', 0);",
                "INSERT INTO creneau (edition_id, date_creneau, heure_debut, heure_fin, famille) VALUES ('" + EDITION + "', '2026-08-14', '09:15', '13:15', 1);",
                "INSERT INTO typologie (edition_id, id, label) VALUES ('" + EDITION + "', 'JEU', 'Jeu');"))
                .when().post("/api/database/import").then().log().ifValidationFails().statusCode(200);
    }

    private static RequestSpecification edition() {
        return given().header("X-Edition-Id", EDITION);
    }

    @Test
    void aCreatedStandJoinsTheLeastPopulatedFamilyAndAnAddedOneMovesNobody() {
        createStand("STAND-B", null);
        createStand("STAND-C", null);
        createStand("STAND-D", null);
        assertThat(families()).as("round-robin on an empty edition").containsExactly(0, 1, 0);

        // Sorts before every other stand: the old rank-based spread would have
        // shifted B, C and D. Here it simply takes the least populated family.
        createStand("AAA-AJOUTE", null);
        assertThat(families()).containsExactly(1, 0, 1, 0);

        // The operator's own choice wins over the balancing.
        createStand("STAND-E", 0);
        assertThat(edition().when().get("/api/stands").then().statusCode(200)
                .extract().jsonPath().getInt("find { it.id == 'STAND-E' }.famille")).isZero();
    }

    @Test
    void aStandStoredWithoutAFamilyGetsOneRecordedAtTheFirstBuild() throws InterruptedException {
        edition().contentType("text/plain").body(String.join("\n",
                "INSERT INTO stand (edition_id, id, nom, effectif_min, effectif_max, reserve_majeurs) VALUES ('" + EDITION + "', 'STAND-SQL', 'SQL', 1, 1, false);",
                "INSERT INTO stand_typologie (edition_id, stand_id, typologie) VALUES ('" + EDITION + "', 'STAND-SQL', 'JEU');",
                "INSERT INTO animateur (edition_id, id, prenom, nom, date_naissance) VALUES ('" + EDITION + "', 'A1', 'Ana', 'Un', '2000-01-01');"))
                .when().post("/api/database/import").then().statusCode(200);
        assertThat((Object) edition().when().get("/api/stands").then().statusCode(200)
                .extract().jsonPath().get("[0].famille")).isNull();

        String jobId = edition().when().post("/api/solve/async/reference-data?seconds=1&reamorcage=AUCUN")
                .then().statusCode(202).extract().path("id");
        for (int i = 0; i < 120; i++) {
            io.restassured.path.json.JsonPath job = edition().when().get("/api/jobs/" + jobId).then().statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                assertThat(job.getString("status")).as(job.getString("error")).isEqualTo("COMPLETED");
                break;
            }
            Thread.sleep(250);
        }

        assertThat(edition().when().get("/api/stands").then().statusCode(200)
                .extract().jsonPath().getInt("[0].famille")).isBetween(0, 1);
    }

    /**
     * A write that does not carry the family leaves it alone. Any client that
     * predates the field — a script, an old bundle — would otherwise wipe it on
     * every save, and the next build would move the stand to another family,
     * voiding every published line of that stand.
     */
    @Test
    void aWriteThatOmitsTheFamilyLeavesItAlone() {
        createStand("STAND-KEEP", 1);
        Map<String, Object> stand = edition().when().get("/api/stands").then().statusCode(200)
                .extract().jsonPath().getList("findAll { it.id == 'STAND-KEEP' }", Map.class).get(0);
        stand.remove("famille");
        stand.put("nom", "Renommé sans la famille");

        edition().contentType("application/json").body(stand)
                .when().put("/api/stands/STAND-KEEP").then().statusCode(200);

        assertThat(edition().when().get("/api/stands").then().statusCode(200).extract().jsonPath()
                .getInt("find { it.id == 'STAND-KEEP' }.famille"))
                .as("the family the operator chose survives a write that says nothing about it")
                .isEqualTo(1);
    }

    /** A family the grid does not have is refused, not stored and silently rewritten. */
    @Test
    void aFamilyBeyondTheGridIsRefused() {
        edition().contentType("application/json")
                .body("{\"id\":\"STAND-HORS\",\"nom\":\"Hors grille\",\"typologiesProposees\":[\"JEU\"],"
                        + "\"effectifMin\":1,\"effectifMax\":1,\"famille\":7}")
                .when().post("/api/stands").then().statusCode(400)
                .body("message", org.hamcrest.Matchers.containsString("famille"));
    }

    private static void createStand(String id, Integer famille) {
        edition().contentType("application/json")
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + id + "\",\"typologiesProposees\":[\"JEU\"],"
                        + "\"effectifMin\":1,\"effectifMax\":1" + (famille == null ? "" : ",\"famille\":" + famille) + "}")
                .when().post("/api/stands").then().statusCode(200);
    }

    /** Families in id order, the order the API lists stands in. */
    private static List<Integer> families() {
        return edition().when().get("/api/stands").then().statusCode(200).extract().jsonPath().getList("famille");
    }
}
