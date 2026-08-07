package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * The diagnostic must be available on the setup screen <em>before</em> any
 * solve: none of these tests ever calls {@code /api/solve}.
 */
@QuarkusTest
class FeasibilityResourceTest {

    @Test
    void diagnostiqueLesDonneesDeReferenceSansAucuneResolution() {
        seedScenario();

        JsonPath report = given()
                .when().get("/api/feasibility")
                .then()
                .statusCode(200)
                .body("feasible", notNullValue())
                .body("message", notNullValue())
                .body("causes.size()", lessThanOrEqualTo(10))
                .extract().jsonPath();

        List<Object> causes = report.getList("causes");
        int totalCauses = report.getInt("totalCauses");
        assertThat(totalCauses).isGreaterThanOrEqualTo(causes.size());
        assertThat(report.getBoolean("feasible")).isEqualTo(totalCauses == 0);
        assertThat(report.getString("message")).isNotBlank();
        assertThat(report.getInt("manqueAnimateurs")).isGreaterThanOrEqualTo(0);
        for (int index = 0; index < causes.size(); index++) {
            assertThat(report.getString("causes[" + index + "].type"))
                    .isIn("CRENEAU_SOUS_EFFECTIF", "STAND_SANS_ANIMATEUR_COMPETENT");
            assertThat(report.getString("causes[" + index + "].severite")).isIn("CRITIQUE", "ELEVE");
            assertThat(report.getList("causes[" + index + "].standIds")).isNotEmpty();
        }
    }

    /**
     * A stand whose typologie nobody masters is structurally impossible to
     * staff: it must be reported as CRITIQUE and ranked first, without waiting
     * for a solver run to fail.
     */
    @Test
    void standSansAnimateurCompetentRemonteEnCauseCritique() {
        given().when().post("/api/planning/reset").then().statusCode(200);

        given()
                .contentType("application/json")
                .body("""
                        {
                          "id":"STAND-FEASIBILITY",
                          "nom":"Stand sans animateur",
                          "typologiesProposees":["STRATEGIE"],
                          "effectifMin":2,
                          "effectifMax":3,
                          "reserveMajeurs":false
                        }
                        """)
                .when().post("/api/stands")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .body("""
                        {
                          "jour":1,
                          "date":"2026-08-01",
                          "heureDebut":"10:00:00",
                          "heureFin":"12:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then()
                .statusCode(200);

        given()
                .when().get("/api/feasibility")
                .then()
                .statusCode(200)
                .body("feasible", equalTo(false))
                .body("causes[0].type", equalTo("STAND_SANS_ANIMATEUR_COMPETENT"))
                .body("causes[0].severite", equalTo("CRITIQUE"))
                .body("causes[0].standIds[0]", equalTo("STAND-FEASIBILITY"))
                .body("causes[0].creneauId", org.hamcrest.Matchers.nullValue())
                .body("causes[0].manque", equalTo(-1))
                .body("totalCauses", equalTo(2));

        // Leave a coherent dataset behind for the other test classes.
        seedScenario();
    }

    private static void seedScenario() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given()
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(204);
    }
}
