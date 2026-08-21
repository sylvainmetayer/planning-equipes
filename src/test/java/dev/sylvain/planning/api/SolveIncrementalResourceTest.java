package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * Incremental re-solve (issue #86) end to end: it starts from the persisted
 * plan, re-opens only the perimeter, and reports who moved.
 *
 * <p>The invariant asserted here is the one the feature lives or dies on:
 * <b>nothing outside the perimeter moves</b>. An incremental solve that
 * quietly reshuffles a validated plan would be worse than a full solve, since
 * nobody would be watching for it.</p>
 */
@QuarkusTest
class SolveIncrementalResourceTest {

    private static final int MAX_POLLS = 240;
    private static final long POLL_INTERVAL_MS = 250;

    @Test
    void repartDuPlanPersisteEtNeBougeQueLePerimetreRouvert() throws InterruptedException {
        planPersiste();
        List<Map<String, Object>> avant = affectationsPersistees();
        int hardAvant = hardScorePersiste();
        String cible = premierAnimateurAffecte(avant);

        JsonPath resultat = solveIncremental("{\"animateurIds\":[\"" + cible + "\"]}");

        // Most of the plan is frozen, and the perimeter did re-open seats.
        assertThat(resultat.getInt("result.statistiques.postesFiges")).isPositive();
        assertThat(resultat.getInt("result.statistiques.postesLiberesManuellement")).isPositive();
        assertThat(resultat.getInt("result.statistiques.postesFiges"))
                .isGreaterThan(resultat.getInt("result.statistiques.postesLiberesManuellement"));

        // Every seat held by someone else is exactly where it was.
        Set<String> apres = triplets(affectationsPersistees());
        for (Map<String, Object> affectation : avant) {
            String animateurId = animateurId(affectation);
            if (animateurId == null || animateurId.equals(cible)) {
                continue;
            }
            assertThat(apres).contains(triplet(affectation));
        }

        // And no hard violation was introduced along the way.
        assertThat(hardScorePersiste()).isGreaterThanOrEqualTo(hardAvant);
    }

    @Test
    void sansPerimetreLeDiffNommeExactementLesEquipesQuiOntChange() throws InterruptedException {
        planPersiste();
        List<Map<String, Object>> avant = affectationsPersistees();
        String cible = premierAnimateurAffecte(avant);

        // A late unavailability: the change the automatic perimeter is meant to
        // pick up on its own, with no body at all on the request.
        rendreIndisponible(cible, premierJourDe(avant, cible));
        JsonPath resultat = solveIncremental(null);

        assertThat(resultat.getInt("result.statistiques.postesLiberes")).isPositive();
        assertThat(resultat.getInt("result.statistiques.postesLiberesManuellement")).isZero();
        // Each reported change spells out both crews, by name.
        List<Map<String, Object>> changements = resultat.getList("result.changements");
        assertThat(changements).isNotEmpty();
        for (Map<String, Object> changement : changements) {
            assertThat(changement.get("standNom")).isNotNull();
            assertThat(changement.get("avant")).isNotEqualTo(changement.get("apres"));
        }
    }

    @Test
    void sansPlanPersisteLaReplanificationEchoueAvecUnMessageExplicite() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);

        attendreSolveurLibre();
        String jobId = given().contentType(ContentType.JSON)
                .when().post("/api/solve/incremental/async?seconds=5")
                .then().statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);

        assertThat(job.getString("status")).isEqualTo("FAILED");
        assertThat(job.getString("error")).contains("Aucun plan persisté");
    }

    /* ------------------------------- Helpers ------------------------------- */

    /** Runs an incremental solve to completion and returns its result. */
    private JsonPath solveIncremental(String corps) throws InterruptedException {
        attendreSolveurLibre();
        var requete = given().contentType(ContentType.JSON);
        if (corps != null) {
            requete = requete.body(corps);
        }
        String jobId = requete
                .when().post("/api/solve/incremental/async?seconds=5")
                .then().statusCode(202)
                .extract().path("id");
        JsonPath job = pollUntilFinished(jobId);
        assertThat(job.getString("status")).isEqualTo("COMPLETED");
        return given().when().get("/api/jobs/" + jobId).then().statusCode(200).extract().jsonPath();
    }

    private List<Map<String, Object>> affectationsPersistees() {
        return given().when().get("/api/planning/persisted")
                .then().statusCode(200)
                .extract().jsonPath().getList("postes");
    }

    @SuppressWarnings("unchecked")
    private static String animateurId(Map<String, Object> affectation) {
        Map<String, Object> animateur = (Map<String, Object>) affectation.get("animateur");
        return animateur == null ? null : (String) animateur.get("id");
    }

    @SuppressWarnings("unchecked")
    private static String triplet(Map<String, Object> affectation) {
        Map<String, Object> stand = (Map<String, Object>) affectation.get("stand");
        Map<String, Object> creneau = (Map<String, Object>) affectation.get("creneau");
        return stand.get("id") + "|" + creneau.get("id") + "|" + animateurId(affectation);
    }

    private static Set<String> triplets(List<Map<String, Object>> affectations) {
        Set<String> triplets = new LinkedHashSet<>();
        for (Map<String, Object> affectation : affectations) {
            if (animateurId(affectation) != null) {
                triplets.add(triplet(affectation));
            }
        }
        return triplets;
    }

    private static String premierAnimateurAffecte(List<Map<String, Object>> affectations) {
        for (Map<String, Object> affectation : affectations) {
            String animateurId = animateurId(affectation);
            if (animateurId != null) {
                return animateurId;
            }
        }
        throw new AssertionError("Le plan persisté ne contient aucune affectation pourvue");
    }

    @SuppressWarnings("unchecked")
    private static String premierJourDe(List<Map<String, Object>> affectations, String animateurId) {
        for (Map<String, Object> affectation : affectations) {
            if (animateurId.equals(animateurId(affectation))) {
                return (String) ((Map<String, Object>) affectation.get("creneau")).get("date");
            }
        }
        throw new AssertionError("Aucune affectation pour " + animateurId);
    }

    /** Adds a day off to an animateur, keeping everything else the referential holds. */
    @SuppressWarnings("unchecked")
    private void rendreIndisponible(String animateurId, String jour) {
        Map<String, Object> animateur = given().when().get("/api/animateurs")
                .then().statusCode(200)
                .extract().jsonPath().<Map<String, Object>>getList("$").stream()
                .filter(candidat -> animateurId.equals(candidat.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Animateur introuvable : " + animateurId));
        List<String> jours = new ArrayList<>(
                (List<String>) animateur.getOrDefault("joursIndisponibles", List.of()));
        jours.add(jour);
        animateur.put("joursIndisponibles", jours);
        given().contentType(ContentType.JSON).body(animateur)
                .when().put("/api/animateurs/" + animateurId)
                .then().statusCode(200);
    }

    /** Hard level of the analysis stored by the last solve — 0 means feasible. */
    private int hardScorePersiste() {
        Integer hardScore = given().when().get("/api/constraints")
                .then().statusCode(200)
                .extract().jsonPath().getObject("hardScore", Integer.class);
        assertThat(hardScore).isNotNull();
        return hardScore;
    }

    private void planPersiste() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
        attendreSolveurLibre();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=5")
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
