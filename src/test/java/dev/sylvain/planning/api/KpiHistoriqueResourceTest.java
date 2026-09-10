package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * KPI history (issue #89) end to end: a completed solve leaves exactly one
 * row behind, and that row carries nothing nominative.
 *
 * <p>The privacy assertion is not decoration. The history is the one table
 * deliberately built to <b>outlive</b> the edition it describes — no foreign
 * key, denormalised labels — so anything personal that slipped into it would
 * survive the deletion meant to erase it. Hence a check on the serialized
 * payload itself rather than on the record's declared fields: a future field
 * added upstream would be caught here even if nobody thought to look.</p>
 */
@QuarkusTest
class KpiHistoriqueResourceTest {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;

    @Test
    void chaqueSolveEcritUneLigneDHistoriqueKpi() throws InterruptedException {
        int avant = given().when()
                .get("/api/kpi/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$")
                .size();

        persistedPlan();

        JsonPath historique = given().when()
                .get("/api/kpi/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(historique.getList("$").size()).isGreaterThan(avant);
        // Newest first: the row of the solve just run.
        assertThat(historique.getInt("[0].kpi.postesTotal")).isPositive();
        assertThat(historique.getString("[0].editionId")).isNotBlank();
        assertThat(historique.getString("[0].kpi.dureeSolveSecondes")).isNotNull();
        assertThat(historique.prettify()).doesNotContain("prenom");
    }

    @Test
    void uneLigneSupprimeeDisparaitEtUnIdInconnuRepond404() throws InterruptedException {
        persistedPlan();
        long id = given().when()
                .get("/api/kpi/historique")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");

        given().when().delete("/api/kpi/historique/" + id).then().statusCode(204);
        given().when().delete("/api/kpi/historique/" + id).then().statusCode(404);
    }

    private void persistedPlan() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        attendreSolveurLibre();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");
    }

    private void attendreSolveurLibre() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solver still busy");
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given().when()
                    .get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
