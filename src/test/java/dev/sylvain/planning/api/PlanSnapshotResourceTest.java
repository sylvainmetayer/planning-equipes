package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * Snapshots (issue #138) end to end: capture the persisted plan, put it back
 * after another solve has overwritten it, and — the case the whole feature
 * exists for — keep a snapshot readable once the créneaux it was computed on
 * are gone.
 */
@QuarkusTest
class PlanSnapshotResourceTest {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;

    /** Loads the sample scenario and solves it once, so a plan is persisted. */
    private void planPersiste() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(204);
        solve();
        assertThat(nombreAffectations()).isPositive();
    }

    private void solve() throws InterruptedException {
        attendreSolveurLibre();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
    }

    private int nombreAffectations() {
        return given()
                .when().get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .extract().jsonPath().getInt("assignments");
    }

    private long capturer(String libelle) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"libelle\":\"" + libelle + "\"}")
                .when().post("/api/planning/snapshots")
                .then()
                .statusCode(201)
                .body("libelle", equalTo(libelle))
                .body("automatique", equalTo(false))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void restaureLePlanApresUnAutreSolve() throws InterruptedException {
        planPersiste();
        long id = capturer("Plan de référence");
        int attendu = nombreAffectations();

        solve();

        given()
                .when().post("/api/planning/snapshots/" + id + "/restore")
                .then()
                .statusCode(200)
                .body("restaure", equalTo(true))
                .body("affectations", equalTo(attendu));
        assertThat(nombreAffectations()).isEqualTo(attendu);
    }

    @Test
    void chaqueSolveCaptureAutomatiquementLePlanPrecedent() throws InterruptedException {
        planPersiste();
        int avant = given()
                .when().get("/api/planning/snapshots")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("$").size();

        solve();

        List<Boolean> automatiques = given()
                .when().get("/api/planning/snapshots")
                .then()
                .statusCode(200)
                .extract().jsonPath().getList("automatique", Boolean.class);
        assertThat(automatiques).hasSizeGreaterThan(avant).contains(true);
    }

    @Test
    void restaurationRefuseeQuandLesReferencesOntDisparu() throws InterruptedException {
        planPersiste();
        long id = capturer("Avant remise à zéro");

        // Wipes stands, créneaux and animateurs: every id the snapshot names is
        // gone, so restoring would produce a plan nobody ever computed.
        given().when().post("/api/planning/reset").then().statusCode(200);

        given()
                .when().post("/api/planning/snapshots/" + id + "/restore")
                .then()
                .statusCode(409)
                .body("referencesManquantes.size()", greaterThan(0));
        assertThat(nombreAffectations()).isZero();
    }

    /**
     * Restoring a snapshot of ANOTHER groupe de créneaux than the active one
     * would silently replace the planning with one computed for a different
     * set of créneaux: refused by default, possible only with {@code ?forcer}.
     */
    @Test
    void restaurationBloqueeQuandLeGroupeActifADiverge() throws InterruptedException {
        planPersiste();
        long id = capturer("Plan du groupe d'origine");
        String groupeOrigine = given()
                .when().get("/api/planning/persisted/resolution")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("groupeCreneauId");
        assertThat(groupeOrigine).isNotNull();

        // The admin prepares and activates another groupe.
        given().contentType(ContentType.JSON)
                .body("{\"id\":\"SNAP-G2\",\"nom\":\"Autre groupe\"}")
                .when().post("/api/groupes-creneaux")
                .then().statusCode(200);
        given().contentType(ContentType.JSON)
                .when().put("/api/groupes-creneaux/SNAP-G2/actif")
                .then().statusCode(204);
        try {
            given()
                    .when().post("/api/planning/snapshots/" + id + "/restore")
                    .then()
                    .statusCode(409)
                    .body("groupeDifferent", equalTo(true))
                    .body("message", org.hamcrest.Matchers.containsString("groupe de créneaux"));

            // The explicit override still works — a deliberate choice.
            given()
                    .when().post("/api/planning/snapshots/" + id + "/restore?forcer=true")
                    .then()
                    .statusCode(200)
                    .body("restaure", equalTo(true));
        } finally {
            given().contentType(ContentType.JSON)
                    .when().put("/api/groupes-creneaux/" + groupeOrigine + "/actif")
                    .then().statusCode(204);
            given().when().delete("/api/groupes-creneaux/SNAP-G2")
                    .then().statusCode(204);
        }
    }

    @Test
    void unInstantaneSurvitALaDisparitionDeSesCreneaux() throws InterruptedException {
        planPersiste();
        String groupeNom = given()
                .when().get("/api/planning/persisted/resolution")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("groupeCreneauNom");

        long id = capturer("Groupe bientôt abandonné");

        // Wipes the créneaux (and with them every poste_affectation row) the
        // snapshot was computed on. A snapshot made of copied rows would be gone
        // with them; a denormalised one is still fully readable — including the
        // group name, kept as text for that very reason.
        given().when().post("/api/planning/reset").then().statusCode(200);
        assertThat(nombreAffectations()).isZero();

        given()
                .when().get("/api/planning/snapshots/" + id)
                .then()
                .statusCode(200)
                .body("meta.libelle", equalTo("Groupe bientôt abandonné"))
                .body("meta.groupeNom", groupeNom == null ? nullValue() : equalTo(groupeNom))
                .body("affectations.size()", greaterThan(0));
    }

    /**
     * The constraints screen must describe the plan actually in place: a
     * restore rewrites {@code poste_affectation} outside of any solve, so the
     * stored analysis is re-derived from the restored plan (issue #167 —
     * after a group switch plus a one-click restore, the screen kept showing
     * the previous solve's hard violations against a plan they no longer
     * described).
     */
    @Test
    void restaurerMetAJourLAnalyseDeContraintes() throws InterruptedException {
        planPersiste();
        long id = capturer("Plan à analyser");
        solve();
        String avant = given()
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getString("analysedAt");
        assertThat(avant).isNotNull();

        given().when().post("/api/planning/snapshots/" + id + "/restore").then().statusCode(200);

        String apres = given()
                .when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getString("analysedAt");
        assertThat(apres).isNotNull();
        assertThat(java.time.Instant.parse(apres)).isAfter(java.time.Instant.parse(avant));
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
                    .then()
                    .statusCode(200)
                    .extract().jsonPath();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getString("status"))) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }
}
