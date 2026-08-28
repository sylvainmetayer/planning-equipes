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

    @Test
    void unCreneauInconnuEstUn404EtUnPosteDunAutreCreneauUn400() throws InterruptedException {
        long creneauId = persistedPlan();
        String posteCible = given().when().get("/api/banc-de-touche/" + creneauId)
                .then().statusCode(200).extract().jsonPath().getString("posteCibleId");

        given().when().get("/api/banc-de-touche/999999").then().statusCode(404);
        given().when().get("/api/banc-de-touche/" + creneauId + "?standId=STAND-INEXISTANT")
                .then().statusCode(404);
        given().when().get("/api/banc-de-touche/999999?posteId=" + posteCible).then().statusCode(404);
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
