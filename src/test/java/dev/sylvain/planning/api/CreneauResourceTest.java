package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The timeslot endpoint's refusals — what it answers to a request it cannot
 * write, and what it must keep accepting.
 */
@QuarkusTest
class CreneauResourceTest {

    private static int countCreneaux() {
        return given().when().get("/api/creneaux").then().statusCode(200).extract().path("size()");
    }

    /**
     * A missing hour used to reach the {@code NOT NULL} column and come back as
     * a 500 with a Sentry alert: a bug's treatment for a bad request.
     */
    @Test
    void unCreneauSansHeureDeFinEstRefuseSansRienEcrire() {
        int avant = countCreneaux();

        given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-03",
                          "heureDebut":"18:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(400)
                .body("message", containsString("heure de fin"));

        assertThat(countCreneaux()).isEqualTo(avant);
    }

    @Test
    void unCreneauSansDateEstRefuse() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "heureDebut":"18:00:00",
                          "heureFin":"22:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(400);
    }

    @Test
    void viderUnCreneauExistantParUnePutEstRefuse() {
        Object id = createCreneauNuit();
        try {
            given()
                    .contentType("application/json")
                    .body("""
                            {
                              "heureDebut":"20:00:00",
                              "heureFin":"00:00:00"
                            }
                            """)
                    .when().put("/api/creneaux/" + id)
                    .then()
                    .statusCode(400);
        } finally {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    /**
     * The case the validation must never catch: an end at or before the start is
     * how the domain writes a timeslot running past midnight — 20:00→00:00 is
     * 240 minutes, not an empty interval.
     */
    @Test
    void unCreneauDeNuitEstAccepteEtReluTelQuel() {
        Object id = createCreneauNuit();
        try {
            given()
                    .when().get("/api/creneaux")
                    .then()
                    .statusCode(200)
                    .body("find { it.id == " + id + " }.heureDebut", startsWith("20:00"))
                    .body("find { it.id == " + id + " }.heureFin", startsWith("00:00"))
                    .body("find { it.id == " + id + " }.date", equalTo("2030-01-04"));
        } finally {
            given().when().delete("/api/creneaux/" + id).then().statusCode(204);
        }
    }

    private static Object createCreneauNuit() {
        return given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-04",
                          "heureDebut":"20:00:00",
                          "heureFin":"00:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .extract().path("id");
    }
}
