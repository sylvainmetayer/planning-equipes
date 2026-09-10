package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Refreshing the Contraintes screen is a diagnostic of the persisted plan, not
 * a solve.
 *
 * <p>It used to launch a full solve whose result was thrown away: the screen
 * then showed the score of a planning that was never persisted and that no
 * other screen would ever display. What is checked here is the opposite
 * property — the analysis describes the plan actually in the database, and is
 * produced without holding the solver.</p>
 */
@QuarkusTest
class ConstraintDiagnosticResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final String EDITION = "DIAGNOSTIC-PLAN";

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    @BeforeEach
    void createTargetEdition() {
        given().contentType("application/json")
                .body("{\"id\":\"" + EDITION + "\",\"nom\":\"Diagnostic du plan\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200);
    }

    @AfterEach
    void dropTargetEdition() {
        given().when().delete("/api/editions/" + EDITION);
    }

    @Test
    void diagnosticDescribesThePersistedPlanAndScoresTheCatalogue() throws InterruptedException {
        importScenario();
        solve();

        JsonPath diagnostic = given().header(HEADER, EDITION)
                .when()
                .post("/api/constraints/diagnostic")
                .then()
                .statusCode(200)
                .body("analysedAt", notNullValue())
                .body("scoreGlobal", notNullValue())
                .body("postesNonPourvus", notNullValue())
                .extract()
                .jsonPath();

        // The catalogue ids must match the solver constraint ids, otherwise the
        // screen would silently show rules without any result.
        List<String> scored = diagnostic.getList("contraintes.findAll { it.score != null }.name");
        assertThat(scored).isNotEmpty();
        assertThat(Set.copyOf(diagnostic.getList("contraintes.name"))).containsAll(scored);

        // Same plan, same analysis: nothing was solved, so nothing moved.
        JsonPath again = given().header(HEADER, EDITION)
                .when()
                .post("/api/constraints/diagnostic")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(again.getString("scoreGlobal")).isEqualTo(diagnostic.getString("scoreGlobal"));
    }

    /**
     * Nothing solved yet: the screen says "no analysis" rather than being
     * refused — that empty state is what it has always shown before the first
     * solve.
     */
    @Test
    void diagnosticWithoutAPersistedPlanReturnsTheEmptyView() {
        given().header(HEADER, EDITION)
                .when()
                .post("/api/constraints/diagnostic")
                .then()
                .statusCode(200)
                .body("analysedAt", nullValue())
                .body("scoreGlobal", nullValue())
                .body("contraintes.size()", greaterThan(0));
    }

    private void importScenario() {
        given().header(HEADER, EDITION)
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
    }

    private void solve() throws InterruptedException {
        awaitIdleSolver();
        String jobId = given().header(HEADER, EDITION)
                .when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            String status = given().when()
                    .get("/api/jobs/" + jobId)
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("status");
            if ("COMPLETED".equals(status)) {
                return;
            }
            assertThat(status).as("le solve de préparation doit aboutir").isIn("PENDING", "RUNNING", "QUEUED");
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solve toujours en cours");
    }

    private void awaitIdleSolver() throws InterruptedException {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Solveur toujours occupé");
    }
}
