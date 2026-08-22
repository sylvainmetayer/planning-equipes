package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
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
    private static final String DEFAUT = "DEFAUT";
    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;

    /** Editions created by a test are dropped, so the shared database is left as found. */
    @AfterEach
    void supprimerLesEditionsCreees() {
        List<Map<String, Object>> editions =
                given().when().get("/api/editions").then().statusCode(200).extract().jsonPath().getList("$");
        for (Map<String, Object> edition : editions) {
            String id = (String) edition.get("id");
            if (!DEFAUT.equals(id)) {
                given().when().delete("/api/editions/" + id);
            }
        }
    }

    @Test
    void compareDeuxInstantanesSansJamaisDeclencherDeResolution() throws InterruptedException {
        persistedPlan(DEFAUT);
        long base = capture(DEFAUT, "Base");
        solve(DEFAUT);
        long variante = capture(DEFAUT, "Variante");

        JsonPath comparaison = comparer(DEFAUT, String.valueOf(base), String.valueOf(variante));

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
    void compareUnInstantaneAuPlanCourant() throws InterruptedException {
        persistedPlan(DEFAUT);
        long base = capture(DEFAUT, "Avant retouche");

        JsonPath comparaison = comparer(DEFAUT, String.valueOf(base), "courant");

        assertThat(comparaison.getLong("base.snapshotId")).isEqualTo(base);
        // The current plan has no snapshot id and no label: naming that side is
        // the UI's job, in the user's own language.
        assertThat(comparaison.getString("variante.snapshotId")).isNull();
        assertThat(comparaison.getString("variante.libelle")).isNull();
        assertThat(comparaison.getInt("variante.kpi.postesTotal"))
                .isEqualTo(comparaison.getInt("base.kpi.postesTotal"));
    }

    @Test
    void compareDeuxEditionsEtSignaleQueLesReferentielsDifferent() throws InterruptedException {
        persistedPlan(DEFAUT);
        long base = capture(DEFAUT, "Édition par défaut");

        createEdition("VARIANTE-2026", "Variante 2026");
        persistedPlan("VARIANTE-2026");
        long variante = capture("VARIANTE-2026", "Édition variante");

        // Asked from the default edition: the comparator reads across editions
        // on purpose, otherwise the variant would be invisible from here.
        JsonPath comparaison = comparer(DEFAUT, String.valueOf(base), String.valueOf(variante));

        assertThat(comparaison.getString("base.editionId")).isEqualTo(DEFAUT);
        assertThat(comparaison.getString("variante.editionId")).isEqualTo("VARIANTE-2026");
        assertThat(comparaison.getString("variante.editionNom")).isEqualTo("Variante 2026");
        assertThat(comparaison.getBoolean("editionsDifferentes")).isTrue();

        // And both are offered as sides, whichever edition the browser sits on.
        List<Object> ids = given().header(HEADER, DEFAUT)
                .when().get("/api/planning/snapshots/comparables")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
        assertThat(ids).contains((int) base, (int) variante);
    }

    @Test
    void unInstantaneInconnuRepond404() {
        given().header(HEADER, DEFAUT)
                .when().get("/api/planning/snapshots/compare?base=999999999&variante=courant")
                .then().statusCode(404);
        given().header(HEADER, DEFAUT)
                .when().get("/api/planning/snapshots/compare?base=courant")
                .then().statusCode(404);
    }

    /* ------------------------------- Helpers ------------------------------- */

    private JsonPath comparer(String editionId, String base, String variante) {
        return given().header(HEADER, editionId)
                .when().get("/api/planning/snapshots/compare?base=" + base + "&variante=" + variante)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    private void createEdition(String id, String nom) {
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + id + "\",\"nom\":\"" + nom + "\"}")
                .when().post("/api/editions")
                .then().statusCode(200);
    }

    private long capture(String editionId, String libelle) {
        return given().header(HEADER, editionId)
                .contentType(ContentType.JSON)
                .body("{\"libelle\":\"" + libelle + "\"}")
                .when().post("/api/planning/snapshots")
                .then().statusCode(201)
                .body("libelle", equalTo(libelle))
                .extract().jsonPath().getLong("id");
    }

    private void persistedPlan(String editionId) throws InterruptedException {
        given().header(HEADER, editionId).when().post("/api/planning/reset").then().statusCode(200);
        given().header(HEADER, editionId)
                .when().post("/api/reference-data/import-scenario?name=scenario.yml")
                .then().statusCode(200);
        solve(editionId);
    }

    private void solve(String editionId) throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given().header(HEADER, editionId)
                .when().post("/api/solve/async/reference-data?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
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
            JsonPath job = given()
                    .when().get("/api/jobs/" + jobId)
                    .then().statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
