package dev.sylvain.planning.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * The banc de touche over HTTP (issue #303): read-only, on the persisted plan,
 * and answering with constraint names the Contraintes screen already knows how
 * to word.
 *
 * <p>What is worth asserting at this level, rather than in
 * {@code CreneauAvailabilityCoherenceTest}, is the transport: no solve is triggered,
 * the refusals map to the right status codes through {@code BusinessError},
 * and every reason really carries the catalogue wording the client renders.</p>
 */
@QuarkusTest
class CreneauAvailabilityResourceTest {

    private static final int MAX_POLLS = 120;
    private static final long POLL_INTERVAL_MS = 250;

    @Test
    void listeLesAnimateursNonAffectesEtLaRaisonDeLeurAbsence() throws InterruptedException {
        long creneauId = persistedPlan();

        JsonPath banc = given()
                .when().get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract().jsonPath();

        assertThat(banc.getLong("creneauId")).isEqualTo(creneauId);
        assertThat(banc.getString("posteCibleId")).isNotBlank();
        assertThat(banc.getInt("total")).isEqualTo(banc.getList("animateurs").size());
        assertThat(banc.getInt("disponibles")).isBetween(0, banc.getInt("total"));
        // Nobody on duty on that créneau is on the bench.
        assertThat(banc.getList("animateurs.animateurId")).doesNotHaveDuplicates();
    }

    /** Every reason is a catalogued constraint, never a wording invented by the view. */
    @Test
    void chaqueMotifPorteLeNomEtLeLibelleDuneContrainteCataloguee() throws InterruptedException {
        long creneauId = persistedPlan();

        JsonPath banc = given()
                .when().get("/api/banc-de-touche/" + creneauId)
                .then()
                .statusCode(200)
                .extract().jsonPath();
        JsonPath catalogue = given()
                .when().get("/api/constraints")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        for (Object motif : banc.getList("animateurs.motifs.flatten()")) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> ligne = (java.util.Map<String, Object>) motif;
            assertThat(catalogue.getList("contraintes.name")).contains(ligne.get("contrainte"));
            assertThat((String) ligne.get("description")).isNotBlank();
            assertThat(ligne.get("niveau")).isIn("HARD", "MEDIUM", "SOFT");
        }
    }

    /** A créneau that exists nowhere is the only genuinely bad request left. */
    @Test
    void seulUnCreneauInexistantEstUn404() throws InterruptedException {
        long creneauId = persistedPlan();
        String posteCible = given().when().get("/api/banc-de-touche/" + creneauId)
                .then().statusCode(200).extract().jsonPath().getString("posteCibleId");

        given().when().get("/api/banc-de-touche/999999").then().statusCode(404);
        given().when().get("/api/banc-de-touche/999999?posteId=" + posteCible).then().statusCode(404);
    }

    /**
     * The regression the screen shipped with. Its créneau selector is fed by
     * the referential, which legitimately holds more créneaux than the plan
     * does — a stand closed then, or a découpage run after the last solve. That
     * used to answer 404 « Créneau inconnu » on a perfectly real créneau, on
     * the very first render, with nothing the user could do about it.
     */
    @Test
    void unCreneauSansSiegeDansLePlanRepond200AvecSonStatut() throws InterruptedException {
        long creneauId = persistedPlan();
        long creneauSansSiege = creneauWithoutSeat(creneauId);

        JsonPath banc = given()
                .when().get("/api/banc-de-touche/" + creneauSansSiege)
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(banc.getString("statut")).isEqualTo("NO_SEAT");
        assertThat(banc.getString("posteCibleId")).isNull();
        assertThat(banc.getList("animateurs")).isEmpty();
        // What lets the screen point at a créneau worth opening.
        assertThat(banc.getList("creneauxAvecSieges", Long.class)).contains(creneauId);
    }

    /** Same for a stand the plan opened no seat for on that créneau. */
    @Test
    void unStandSansSiegeSurLeCreneauRepond200AvecSonStatut() throws InterruptedException {
        long creneauId = persistedPlan();

        given().when().get("/api/banc-de-touche/" + creneauId + "?standId=STAND-INEXISTANT")
                .then().statusCode(200)
                .body("statut", org.hamcrest.Matchers.equalTo("NO_SEAT"));
    }

    /**
     * Opened before any solve — the state a new user is in. The screen has to
     * be able to say « lancez une résolution », which it cannot do if the call
     * fails.
     */
    @Test
    void sansAucunPlanEnregistreLEcranRecoitUneReponseExploitable() {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
        Long creneauId = given().when().get("/api/creneaux")
                .then().statusCode(200)
                .extract().jsonPath().getLong("[0].id");

        JsonPath banc = given()
                .when().get("/api/banc-de-touche/" + creneauId)
                .then().statusCode(200)
                .extract().jsonPath();

        assertThat(banc.getString("statut")).isEqualTo("NO_PLAN");
        assertThat(banc.getList("animateurs")).isEmpty();
        assertThat(banc.getList("creneauxAvecSieges")).isEmpty();
    }

    /**
     * A créneau of the referential the solved plan put no seat on. The scenario
     * is solved over every créneau, so one is created here rather than looked
     * for — which is also exactly how the real case arises: a créneau added
     * after the last solve.
     */
    private long creneauWithoutSeat(long creneauExistant) {
        long ajoute = given()
                .contentType("application/json")
                .body("""
                        {
                          "date":"2030-01-05",
                          "heureDebut":"03:00:00",
                          "heureFin":"04:00:00"
                        }
                        """)
                .when().post("/api/creneaux")
                .then().statusCode(200)
                .extract().jsonPath().getLong("id");
        assertThat(ajoute).isNotEqualTo(creneauExistant);
        return ajoute;
    }

    /** Loads the sample scenario, solves it once, and returns a créneau of the persisted plan. */
    private long persistedPlan() throws InterruptedException {
        given().when().post("/api/planning/reset").then().statusCode(200);
        given().when().post("/api/reference-data/import-scenario?name=scenario.yml").then().statusCode(200);
        waitForIdleSolver();
        String jobId = given()
                .when().post("/api/solve/async/reference-data?seconds=1")
                .then().statusCode(202)
                .extract().path("id");
        assertThat(pollUntilFinished(jobId).getString("status")).isEqualTo("COMPLETED");
        Long creneauId = given().when().get("/api/creneaux")
                .then().statusCode(200)
                .extract().jsonPath().getLong("[0].id");
        assertThat(creneauId).isNotNull();
        return creneauId;
    }

    private void waitForIdleSolver() throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            if (given().when().get("/api/jobs/active").then().extract().statusCode() == 204) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Le solveur ne s'est jamais libéré");
    }

    private JsonPath pollUntilFinished(String jobId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            JsonPath job = given().when().get("/api/jobs/" + jobId)
                    .then().statusCode(200)
                    .extract().jsonPath();
            String status = job.getString("status");
            if (!"RUNNING".equals(status) && !"QUEUED".equals(status)) {
                return job;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("Le job " + jobId + " ne s'est jamais terminé");
    }
}
