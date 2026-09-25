package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Snapshots (issue #138) end to end: capture the persisted plan, put it back
 * after another solve has overwritten it, and — the case the whole feature
 * exists for — keep a snapshot readable once the créneaux it was computed on
 * are gone.
 */
@QuarkusTest
class PlanSnapshotResourceTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** Loads the sample scenario and solves it once, so a plan is persisted. */
    private void persistedPlan() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when()
                .post("/api/reference-data/import-scenario?name=scenario.yml")
                .then()
                .statusCode(200);
        solve();
        assertThat(affectationCount()).isPositive();
    }

    private void solve() {
        attendreSolveurLibre();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=1")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
    }

    private int affectationCount() {
        return given().when()
                .get("/api/planning/persisted/count")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("assignments");
    }

    private long capture(String libelle) {
        return given().contentType(ContentType.JSON)
                .body("{\"libelle\":\"" + libelle + "\"}")
                .when()
                .post("/api/planning/snapshots")
                .then()
                .statusCode(201)
                .body("libelle", equalTo(libelle))
                .body("automatique", equalTo(false))
                .extract()
                .jsonPath()
                .getLong("id");
    }

    @Test
    void restaureLePlanApresUnAutreSolve() {
        persistedPlan();
        long id = capture("Plan de référence");
        int attendu = affectationCount();

        solve();

        given().when()
                .post("/api/planning/snapshots/" + id + "/restore")
                .then()
                .statusCode(200)
                .body("restaure", equalTo(true))
                .body("affectations", equalTo(attendu));
        assertThat(affectationCount()).isEqualTo(attendu);
    }

    @Test
    void chaqueSolveCaptureAutomatiquementLePlanPrecedent() {
        persistedPlan();
        int avant = given().when()
                .get("/api/planning/snapshots")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$")
                .size();

        solve();

        List<Boolean> automatiques = given().when()
                .get("/api/planning/snapshots")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("automatique", Boolean.class);
        assertThat(automatiques).hasSizeGreaterThan(avant).contains(true);
    }

    @Test
    void restaurationRefuseeQuandLesReferencesOntDisparu() {
        persistedPlan();
        long id = capture("Avant remise à zéro");

        // Wipes stands, créneaux and animateurs: every id the snapshot names is
        // gone, so restoring would produce a plan nobody ever computed.
        given().when().post("/api/planning/reset").then().statusCode(200);

        given().when()
                .post("/api/planning/snapshots/" + id + "/restore")
                .then()
                .statusCode(409)
                .body("referencesManquantes.size()", greaterThan(0));
        assertThat(affectationCount()).isZero();
    }

    @Test
    void unInstantaneSurvitALaDisparitionDeSesCreneaux() {
        persistedPlan();
        long id = capture("Grille bientôt abandonnée");

        // Wipes the créneaux (and with them every poste_affectation row) the
        // snapshot was computed on. A snapshot made of copied rows would be gone
        // with them; a denormalised one is still fully readable — including the
        // group name, kept as text for that very reason.
        given().when().post("/api/planning/reset").then().statusCode(200);
        assertThat(affectationCount()).isZero();

        given().when()
                .get("/api/planning/snapshots/" + id)
                .then()
                .statusCode(200)
                .body("meta.libelle", equalTo("Grille bientôt abandonnée"))
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
    void restaurerMetAJourLAnalyseDeContraintes() {
        persistedPlan();
        long id = capture("Plan à analyser");
        solve();
        String avant = given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("analysedAt");
        assertThat(avant).isNotNull();

        given().when().post("/api/planning/snapshots/" + id + "/restore").then().statusCode(200);

        String apres = given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("analysedAt");
        assertThat(apres).isNotNull();
        assertThat(java.time.Instant.parse(apres)).isAfter(java.time.Instant.parse(avant));
    }

    /**
     * The guard of issue #313, seen from the API: a restore asked while a solve
     * holds this edition's solver is a 409 whose body names the run — the same
     * shape as every refused referential write — and nothing is written.
     */
    @Test
    void restoreAnswers409WhileASolveRuns() {
        persistedPlan();
        long id = capture("Pendant le solve");
        int avant = affectationCount();

        attendreSolveurLibre();
        String jobId = given().when()
                .post("/api/solve/async/reference-data?seconds=30")
                .then()
                .statusCode(202)
                .extract()
                .path("id");
        try {
            given().when()
                    .post("/api/planning/snapshots/" + id + "/restore")
                    .then()
                    .statusCode(409)
                    .body("id", equalTo(jobId))
                    .body("message", containsString("résolution est en cours"));
            assertThat(affectationCount()).isEqualTo(avant);
        } finally {
            given().when().post("/api/jobs/" + jobId + "/cancel");
            pollUntilFinished(jobId);
        }
    }

    /**
     * Freshness guard (issue #170), seen from the API: a snapshot whose
     * referential has since been written is a 409 carrying {@code perime} and
     * the date of that change — and nothing is restored — until the caller
     * says {@code ?forcer=true}. The refusal and the override share the same
     * route on purpose: what makes forcing deliberate is having been told
     * first, not a second endpoint.
     */
    @Test
    void restaurationRefuseeQuandLeReferentielABougeDepuisLaCapture() {
        persistedPlan();
        long id = capture("Avant de toucher au référentiel");
        int attendu = affectationCount();
        solve();
        assertThat(affectationCount()).isPositive();

        ContrainteBasculee contrainte = contrainteSouple();
        basculerContrainte(contrainte.nom(), false);
        try {
            given().when()
                    .post("/api/planning/snapshots/" + id + "/restore")
                    .then()
                    .statusCode(409)
                    .body("perime", equalTo(true))
                    .body("referencesManquantes.size()", equalTo(0))
                    .body("referenceModifieLe", notNullValue())
                    .body("message", containsString("modifié depuis cette capture"));

            given().when()
                    .post("/api/planning/snapshots/" + id + "/restore?forcer=true")
                    .then()
                    .statusCode(200)
                    .body("restaure", equalTo(true))
                    .body("affectations", equalTo(attendu));
            assertThat(affectationCount()).isEqualTo(attendu);
        } finally {
            basculerContrainte(contrainte.nom(), contrainte.actifAvant());
            // A hand-made snapshot is never purged, so leaving one behind moves
            // the population the retention test of this class counts.
            oublier(id);
        }
    }

    /**
     * The listing carries the badge the screen shows, so it has to be right on
     * both sides of a referential write — a snapshot taken after it is
     * {@code perime: false}, and the same one is {@code true} a toggle later.
     */
    @Test
    void laListeAnnonceLaFraicheurDeChaqueInstantane() {
        persistedPlan();
        long id = capture("Fraîcheur listée");

        try {
            assertThat(staleInListing(id)).isFalse();

            ContrainteBasculee contrainte = contrainteSouple();
            basculerContrainte(contrainte.nom(), false);
            try {
                assertThat(staleInListing(id)).isTrue();
            } finally {
                basculerContrainte(contrainte.nom(), contrainte.actifAvant());
            }
        } finally {
            oublier(id);
        }
    }

    /** Drops a snapshot this class captured, so the next test sees the population it expects. */
    private void oublier(long id) {
        given().when().delete("/api/planning/snapshots/" + id).then().statusCode(204);
    }

    private boolean staleInListing(long id) {
        return given().when()
                .get("/api/planning/snapshots")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getBoolean("find { it.id == " + id + " }.perime");
    }

    /**
     * A constraint the toggle may flip freely — never a protected or a legal
     * one: this test wants a referential write, not an argument about the Code
     * du travail. Its state travels with it, so the restore puts back what was
     * there instead of assuming « actif » — this edition is shared with every
     * other test of the run.
     */
    private ContrainteBasculee contrainteSouple() {
        JsonPath contraintes = given().when()
                .get("/api/constraints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        List<String> souples = contraintes.getList(
                "contraintes.findAll { it.niveau == 'SOFT' && !it.protegee && !it.legale }.name", String.class);
        assertThat(souples).isNotEmpty();
        String nom = souples.get(0);
        return new ContrainteBasculee(
                nom, contraintes.getBoolean("contraintes.find { it.name == '" + nom + "' }.actif"));
    }

    /** @param actifAvant the state to put back, whatever this test did to it */
    private record ContrainteBasculee(String nom, boolean actifAvant) {}

    private void basculerContrainte(String nom, boolean actif) {
        given().contentType(ContentType.JSON)
                .body("{\"actif\":" + actif + "}")
                .when()
                .put("/api/constraints/" + nom)
                .then()
                .statusCode(200);
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
