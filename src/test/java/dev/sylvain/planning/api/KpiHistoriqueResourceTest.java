package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
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

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    @Test
    void everySolveWritesAKpiHistoryLine() {
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
        assertThat(historique.getList("$")).hasSizeGreaterThan(avant);
        // Newest first: the row of the solve just run.
        assertThat(historique.getInt("[0].kpi.postesTotal")).isPositive();
        assertThat(historique.getString("[0].editionId")).isNotBlank();
        assertThat(historique.getString("[0].kpi.dureeSolveSecondes")).isNotNull();
        assertThat(historique.prettify()).doesNotContain("prenom");
    }

    @Test
    void uneLigneSupprimeeDisparaitEtUnIdInconnuRepond404() {
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

    private void persistedPlan() {
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

    private void attendreSolveurLibre() {
        await().alias("Solver still busy")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() ->
                        given().when().get("/api/jobs/active").then().extract().statusCode() == 204);
    }

    private JsonPath pollUntilFinished(String jobId) {
        return await().alias("Job " + jobId + " did not finish in time")
                .atMost(POLL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(
                        () -> given().when()
                                .get("/api/jobs/" + jobId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .jsonPath(),
                        job -> List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status")));
    }
}
