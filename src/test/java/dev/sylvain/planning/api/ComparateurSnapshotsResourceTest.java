package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A/B comparator (issue #70) end to end: comparing two captures, comparing a
 * capture with the plan currently persisted, and comparing across two
 * editions — the case that matters since #172, where the variant of an edition
 * <b>is</b> another edition.
 *
 * <p>The invariant the whole feature rests on is checked explicitly: comparing
 * never launches a solve.</p>
 */
@QuarkusTest
class ComparateurSnapshotsResourceTest {

    private static final String HEADER = "X-Edition-Id";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** The default edition of the test database, resolved rather than assumed (ADR 0050). */
    private String defaut;

    @BeforeEach
    void resolveDefaultEdition() {
        defaut = listEditions().stream()
                .filter(edition -> Boolean.TRUE.equals(edition.get("defaut")))
                .map(edition -> (String) edition.get("id"))
                .findFirst()
                .orElseThrow();
    }

    /** Editions created by a test are dropped, so the shared database is left as found. */
    @AfterEach
    void dropCreatedEditions() {
        for (Map<String, Object> edition : listEditions()) {
            String id = (String) edition.get("id");
            if (!defaut.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    private static List<Map<String, Object>> listEditions() {
        return given().when()
                .get("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");
    }

    @Test
    void comparesTwoSnapshotsWithoutEverTriggeringASolve() {
        persistedPlan(defaut);
        long base = capture(defaut, "Base");
        solve(defaut);
        long variante = capture(defaut, "Variante");

        JsonPath comparaison = comparer(defaut, String.valueOf(base), String.valueOf(variante));

        assertThat(comparaison.getLong("base.snapshotId")).isEqualTo(base);
        assertThat(comparaison.getString("base.libelle")).isEqualTo("Base");
        assertThat(comparaison.getString("variante.libelle")).isEqualTo("Variante");
        // Both captures carry their own KPI, so nothing had to be recomputed.
        assertThat(comparaison.getBoolean("base.kpiRecalcule")).isFalse();
        assertThat(comparaison.getInt("base.kpi.postesTotal")).isPositive();
        assertThat(comparaison.getInt("variante.kpi.postesTotal")).isPositive();
        assertThat(comparaison.getBoolean("editionsDifferentes")).isFalse();
        assertThat(comparaison.getBoolean("volumetriesDifferentes")).isFalse();
        assertThat(comparaison.getList("diffViolations")).isNotEmpty();
        // The whole promise of the feature: reading metrics, not solving.
        given().when().get("/api/jobs/active").then().statusCode(204);
        // And nothing nominative travels, like the KPI it reads.
        assertThat(comparaison.prettify()).doesNotContain("prenom");
    }

    @Test
    void comparesASnapshotWithTheCurrentPlan() {
        persistedPlan(defaut);
        long base = capture(defaut, "Avant retouche");

        JsonPath comparaison = comparer(defaut, String.valueOf(base), "courant");

        assertThat(comparaison.getLong("base.snapshotId")).isEqualTo(base);
        // The current plan has no snapshot id and no label: naming that side is
        // the UI's job, in the user's own language.
        assertThat(comparaison.getString("variante.snapshotId")).isNull();
        assertThat(comparaison.getString("variante.libelle")).isNull();
        assertThat(comparaison.getInt("variante.kpi.postesTotal"))
                .isEqualTo(comparaison.getInt("base.kpi.postesTotal"));
    }

    @Test
    void comparesTwoEditionsAndReportsThatTheirReferentialsDiffer() {
        persistedPlan(defaut);
        long base = capture(defaut, "Édition par défaut");

        String varianteEdition = createEdition("Variante 2026");
        persistedPlan(varianteEdition);
        long variante = capture(varianteEdition, "Édition variante");

        // Asked from the default edition: the comparator reads across editions
        // on purpose, otherwise the variant would be invisible from here.
        JsonPath comparaison = comparer(defaut, String.valueOf(base), String.valueOf(variante));

        assertThat(comparaison.getString("base.editionId")).isEqualTo(defaut);
        assertThat(comparaison.getString("variante.editionId")).isEqualTo(varianteEdition);
        assertThat(comparaison.getString("variante.editionNom")).isEqualTo("Variante 2026");
        assertThat(comparaison.getBoolean("editionsDifferentes")).isTrue();

        // And both are offered as sides, whichever edition the browser sits on.
        List<Object> ids = given().header(HEADER, defaut)
                .when()
                .get("/api/planning/snapshots/comparables")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id");
        assertThat(ids).contains((int) base, (int) variante);
    }

    @Test
    void anUnknownSnapshotAnswers404() {
        given().header(HEADER, defaut)
                .when()
                .get("/api/planning/snapshots/compare?base=999999999&variante=courant")
                .then()
                .statusCode(404);
        given().header(HEADER, defaut)
                .when()
                .get("/api/planning/snapshots/compare?base=courant")
                .then()
                .statusCode(404);
    }

    /* ------------------------------- Helpers ------------------------------- */

    private JsonPath comparer(String editionId, String base, String variante) {
        return given().header(HEADER, editionId)
                .when()
                .get("/api/planning/snapshots/compare?base=" + base + "&variante=" + variante)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    /** Creates an edition and answers the id the application drew for it. */
    private String createEdition(String nom) {
        return given().contentType(ContentType.JSON)
                .body("{\"nom\":\"" + nom + "\"}")
                .when()
                .post("/api/editions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private long capture(String editionId, String libelle) {
        return given().header(HEADER, editionId)
                .contentType(ContentType.JSON)
                .body("{\"libelle\":\"" + libelle + "\"}")
                .when()
                .post("/api/planning/snapshots")
                .then()
                .statusCode(201)
                .body("libelle", equalTo(libelle))
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private void persistedPlan(String editionId) {
        given().header(HEADER, editionId)
                .when()
                .post("/api/planning/reset")
                .then()
                .statusCode(200);
        given().header(HEADER, editionId)
                .when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        solve(editionId);
    }

    private void solve(String editionId) {
        attendreSolveurLibre();
        String jobId = given().header(HEADER, editionId)
                .when()
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
