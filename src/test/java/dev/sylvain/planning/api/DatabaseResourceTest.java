package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DatabaseResourceTest {

    /**
     * The browser uploads the dump as {@code application/sql}: tell RestAssured
     * to encode that content type as plain text.
     */
    private static RequestSpecification sqlRequest(String script) {
        return given()
                .config(RestAssured.config().encoderConfig(
                        EncoderConfig.encoderConfig().encodeContentTypeAs("application/sql", ContentType.TEXT)))
                .contentType("application/sql")
                .body(script);
    }

    @Test
    void exportedDumpCanBeReplayedAndRestoresTheDataset() {
        // Reset first for a deterministic baseline (no leftovers from another
        // test), then seed: reset alone empties the database and leaves
        // nothing to export (see PlanningResourceTest.resetEmptiesTheDatabase).
        given()
                .when().post("/api/planning/reset")
                .then()
                .statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        int animateurs = given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("$").size();
        assertThat(animateurs).isPositive();

        String dump = given()
                .when().get("/api/database/export")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString(".sql"))
                .extract().asString();
        assertThat(dump).contains("DELETE FROM animateur;").contains("INSERT INTO animateur (");

        // Replaying the dump on a wiped database restores the exact same rows.
        sqlRequest(dump)
                .when().post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200)
                .body("size()", equalTo(animateurs));
    }

    @Test
    void exportedDumpIncludesGroupeCreneauSoCreneauForeignKeysReplay() {
        given()
                .when().post("/api/planning/reset")
                .then()
                .statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body("{\"id\":\"CONTINU\",\"nom\":\"Continu\"}")
                .when().post("/api/groupes-creneaux")
                .then()
                .statusCode(200);

        String dump = given()
                .when().get("/api/database/export")
                .then()
                .statusCode(200)
                .extract().asString();
        assertThat(dump).contains("INSERT INTO groupe_creneau (").contains("'CONTINU'");

        sqlRequest(dump)
                .when().post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));
    }

    @Test
    void exportedDumpIncludesParametresAndSurvivesReplay() {
        given()
                .when().post("/api/planning/reset")
                .then()
                .statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body("{\"dureeHebdomadaireMaxMinutes\":2760,\"dureeHebdomadaireMaxMineurMinutes\":2100,"
                        + "\"pauseMinimaleEntreVacationsMinutes\":45,\"reposQuotidienMinimalMinutes\":660}")
                .when().put("/api/parametres-legaux")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("{\"dureeResolutionSecondes\":42}")
                .when().put("/api/parametres-solveur")
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("{\"actif\":false,\"motif\":\"test\",\"modifieParUtilisateurId\":\"ui\"}")
                .when().put("/api/constraints/dureeHebdomadaireMax")
                .then()
                .statusCode(200);

        String dump = given()
                .when().get("/api/database/export")
                .then()
                .statusCode(200)
                .extract().asString();
        assertThat(dump)
                .contains("INSERT INTO parametres_legaux (").contains("2760")
                .contains("INSERT INTO parametres_decoupage (")
                .contains("INSERT INTO parametres_solveur (").contains("42")
                .contains("INSERT INTO constraint_toggle (").contains("dureeHebdomadaireMax");

        sqlRequest(dump)
                .when().post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        given()
                .when().get("/api/parametres-legaux")
                .then()
                .statusCode(200)
                .body("dureeHebdomadaireMaxMinutes", equalTo(2760));

        given()
                .when().get("/api/parametres-solveur")
                .then()
                .statusCode(200)
                .body("dureeResolutionSecondes", equalTo(42));
    }

    @Test
    void exportedDumpRestoresIdentitySequencesSoNewRowsDoNotCollide() {
        given()
                .when().post("/api/planning/reset")
                .then()
                .statusCode(200);

        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);

        String dump = given()
                .when().get("/api/database/export")
                .then()
                .statusCode(200)
                .extract().asString();

        sqlRequest(dump)
                .when().post("/api/database/import")
                .then()
                .statusCode(200)
                .body("statements", greaterThan(0));

        // A new créneau created after the replay must get a fresh id, not one
        // that collides with a row the dump just re-inserted with an explicit
        // identity value (see resyncIdentitySequences).
        given()
                .contentType(ContentType.JSON)
                .body("{\"date\":\"2099-01-01\",\"heureDebut\":\"09:00:00\",\"heureFin\":\"10:00:00\"}")
                .when().post("/api/creneaux")
                .then()
                .statusCode(200);
    }

    @Test
    void importRejectsStatementsOutsideTheAllowedScope() {
        sqlRequest("DROP TABLE animateur;")
                .when().post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("Only INSERT, DELETE and TRUNCATE"));

        sqlRequest("DELETE FROM flyway_schema_history;")
                .when().post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("not allowed"));

        // A rejected script must not have touched the database.
        given()
                .when().get("/api/animateurs")
                .then()
                .statusCode(200);
    }

    @Test
    void importRejectsAnEmptyScript() {
        sqlRequest("-- nothing to replay\n")
                .when().post("/api/database/import")
                .then()
                .statusCode(400)
                .body("message", containsString("does not contain any statement"));
    }
}
