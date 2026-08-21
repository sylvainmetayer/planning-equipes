package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * End-to-end round trip of {@code /api/verrouillages} against the real
 * PostgreSQL container (dev services), so the Flyway table, its check
 * constraint and the JDBC mapping are all exercised.
 */
@QuarkusTest
class VerrouillageResourceTest {

    private static String creerJour(String jour) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"JOUR\",\"jour\":\"" + jour + "\",\"raison\":\"Journée validée\"}")
                .when().post("/api/verrouillages")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("type", equalTo("JOUR"))
                .body("jour", equalTo(jour))
                .extract().path("id");
    }

    @Test
    void unVerrouillageJourEstCreeListePuisSupprime() {
        String id = creerJour("2026-07-12");
        try {
            given().when().get("/api/verrouillages")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == '" + id + "' }.raison", equalTo("Journée validée"));
        } finally {
            given().when().delete("/api/verrouillages/" + id).then().statusCode(204);
        }
        given().when().get("/api/verrouillages")
                .then()
                .statusCode(200)
                .body("findAll { it.id == '" + id + "' }.size()", equalTo(0));
    }

    /** Locking twice is not an error — it is already locked. */
    @Test
    void verrouillerDeuxFoisLaMemeCibleNeCreePasDeDoublon() {
        String premier = creerJour("2026-07-13");
        String second = creerJour("2026-07-13");
        try {
            given().when().get("/api/verrouillages")
                    .then()
                    .statusCode(200)
                    .body("findAll { it.type == 'JOUR' && it.jour == '2026-07-13' }.size()", equalTo(1));
        } finally {
            given().when().delete("/api/verrouillages/" + premier).then().statusCode(204);
            given().when().delete("/api/verrouillages/" + second).then().statusCode(204);
        }
    }

    /**
     * A payload naming two targets at once keeps only the one of its type. That
     * used to be guaranteed by every validation branch remembering to set the
     * four other columns to {@code null}; forgetting one passed the
     * {@code CHECK} constraint and left a lock aiming at two things in the
     * database. It is structural now: the target is a sealed hierarchy that
     * cannot carry two, and a single place lays it back into the columns.
     */
    @Test
    void unPayloadPortantDeuxCiblesNeGardeQueCelleDeSonType() {
        String id = given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"JOUR\",\"jour\":\"2026-07-14\","
                        + "\"animateurId\":\"INTRUS\",\"standId\":\"INTRUS\",\"creneauId\":42,"
                        + "\"raison\":\"Journée validée\"}")
                .when().post("/api/verrouillages")
                .then()
                .statusCode(200)
                .body("type", equalTo("JOUR"))
                .body("jour", equalTo("2026-07-14"))
                .body("animateurId", nullValue())
                .body("standId", nullValue())
                .body("creneauId", nullValue())
                .extract().path("id");

        try {
            given().when().get("/api/verrouillages")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == '" + id + "' }.animateurId", nullValue())
                    .body("find { it.id == '" + id + "' }.standId", nullValue())
                    .body("find { it.id == '" + id + "' }.creneauId", nullValue());
        } finally {
            given().when().delete("/api/verrouillages/" + id).then().statusCode(204);
        }
    }

    @Test
    void unTypeSansCibleCorrespondanteEstRefuse() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"STAND\"}")
                .when().post("/api/verrouillages")
                .then()
                .statusCode(400)
                .body("message", containsString("stand id"));
    }

    @Test
    void uneCibleInconnueEstRefusee() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"ANIMATEUR\",\"animateurId\":\"ANIMATEUR-INEXISTANT\"}")
                .when().post("/api/verrouillages")
                .then()
                .statusCode(400)
                .body("message", containsString("Animateur inconnu"));
    }
}
